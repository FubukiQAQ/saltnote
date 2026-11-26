use chrono::{TimeZone, Utc};
use loro::{ExportMode, LoroDoc, LoroList, LoroMap, LoroValue, UpdateOptions, VersionVector};
use parking_lot::{Mutex, RwLock};
use salt_types::{
    content_addressed_asset_rel_path, default_halo_presets, new_ulid, now_utc_ms,
    parse_flashcard_drafts, sha256_hex, validate_router_config_no_plaintext_keys, CanvasImageNode,
    CanvasSnapshot, DeltaEntry, DeltaStroke, DeviceManifest, DeviceState, FlashCard,
    HaloAnnotation, HaloPreset, InboxAiEnrichment, InboxConsumption, InboxItem, InboxKind,
    InboxPayload, InboxState, LocalSyncPrefs, PageMeta, PageMode, PageSnapshot, PageSummary,
    PairedPeer, ReviewLog, RouterConfig, SaltError, SaltResult, StrokeTransform, TranscriptStatus,
    WebDavConfig,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::sync::broadcast;

/// Physical Vault directory layout strictly matching Alpha MVP Section 2.2.
#[derive(Debug, Clone)]
pub struct VaultPaths {
    pub root: PathBuf,
}

impl VaultPaths {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn pages_dir(&self) -> PathBuf {
        self.root.join("pages")
    }

    pub fn assets_dir(&self) -> PathBuf {
        self.root.join("assets")
    }

    pub fn saltnote_dir(&self) -> PathBuf {
        self.root.join(".saltnote")
    }

    pub fn config_dir(&self) -> PathBuf {
        self.saltnote_dir().join("config")
    }

    pub fn router_config_path(&self) -> PathBuf {
        self.config_dir().join("router.json")
    }

    pub fn halo_presets_path(&self) -> PathBuf {
        self.config_dir().join("halo_presets.json")
    }

    pub fn webdav_config_path(&self) -> PathBuf {
        self.config_dir().join("webdav.json")
    }

    pub fn sync_dir(&self) -> PathBuf {
        self.saltnote_dir().join("sync")
    }

    pub fn manifests_dir(&self) -> PathBuf {
        self.sync_dir().join("manifests")
    }

    pub fn deltas_dir(&self) -> PathBuf {
        self.sync_dir().join("deltas")
    }

    pub fn device_deltas_dir(&self, device_id: &str) -> PathBuf {
        self.deltas_dir().join(device_id)
    }

    pub fn manifest_path(&self, device_id: &str) -> PathBuf {
        self.manifests_dir().join(format!("{device_id}.json"))
    }

    pub fn local_dir(&self) -> PathBuf {
        self.saltnote_dir().join("local")
    }

    pub fn sqlite_path(&self) -> PathBuf {
        self.local_dir().join("index.sqlite")
    }

    pub fn slices_cache_dir(&self) -> PathBuf {
        self.local_dir().join("cache").join("slices")
    }

    pub fn wal_path(&self) -> PathBuf {
        self.local_dir().join("wal.bin")
    }

    pub fn device_state_path(&self) -> PathBuf {
        self.local_dir().join("device_state.json")
    }

    pub fn paired_peers_path(&self) -> PathBuf {
        self.local_dir().join("paired_peers.json")
    }

    pub fn sync_prefs_path(&self) -> PathBuf {
        self.local_dir().join("sync_prefs.json")
    }

    pub fn ensure_structure(&self) -> SaltResult<()> {
        for dir in [
            self.pages_dir(),
            self.assets_dir(),
            self.config_dir(),
            self.manifests_dir(),
            self.deltas_dir(),
            self.local_dir(),
            self.slices_cache_dir(),
        ] {
            fs::create_dir_all(&dir)
                .map_err(|e| SaltError::Io(format!("Failed to create dir {}: {e}", dir.display())))?;
        }
        Ok(())
    }
}

/// Wire packet stored in `.saltnote/sync/deltas/{device_id}/{ts}_{seq}.delta` and exchanged over P2P.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeltaPacket {
    pub page_id: String,
    pub device_id: String,
    pub seq: u64,
    pub created_at_utc: i64,
    pub loro_bytes: Vec<u8>,
}

/// Reserved document ID for Vault-level CRDT root state (e.g. `inbox` LoroMap).
pub const VAULT_ROOT_DOC_ID: &str = "__vault_root__";

/// Events emitted on every local edit or remote CRDT import to drive `salt-intel` (FTS5 + FSRS).
#[derive(Debug, Clone)]
pub enum DocChangeEvent {
    PageUpdated {
        page_id: String,
        meta: PageMeta,
        body_markdown: String,
    },
    PageDeleted {
        page_id: String,
    },
    CardsUpdated {
        page_id: String,
        cards: Vec<FlashCard>,
        review_logs: Vec<ReviewLog>,
    },
    InboxUpdated,
}

struct PageEntry {
    doc: LoroDoc,
    exported_vv: VersionVector,
    dirty_since_export: bool,
    markdown_file: Option<String>,
}

/// Thread-safe Loro CRDT Vault manager with local crash-safe WAL, `.md` projection,
/// single-writer `.delta` export, and remote delta import.
pub struct VaultStore {
    paths: VaultPaths,
    peer_id: u64,
    device_state: RwLock<DeviceState>,
    root_doc: RwLock<PageEntry>,
    pages: RwLock<HashMap<String, PageEntry>>,
    events_tx: broadcast::Sender<DocChangeEvent>,
    sync_revision: AtomicU64,
    inbox_sync_revision: AtomicU64,
    flush_lock: Mutex<()>,
    paired_peers_lock: Mutex<()>,
}

impl VaultStore {
    pub fn open(root: impl Into<PathBuf>, device_name: Option<String>) -> SaltResult<Self> {
        let paths = VaultPaths::new(root);
        paths.ensure_structure()?;

        // Initialize default config files if absent
        if !paths.router_config_path().exists() {
            let cfg = RouterConfig::default();
            let json = serde_json::to_string_pretty(&cfg)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            fs::write(paths.router_config_path(), json)
                .map_err(|e| SaltError::Io(e.to_string()))?;
        }
        if !paths.halo_presets_path().exists() {
            let presets = default_halo_presets();
            let json = serde_json::to_string_pretty(&presets)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            fs::write(paths.halo_presets_path(), json)
                .map_err(|e| SaltError::Io(e.to_string()))?;
        }
        if !paths.webdav_config_path().exists() {
            let cfg = WebDavConfig::default();
            let json = serde_json::to_string_pretty(&cfg)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            fs::write(paths.webdav_config_path(), json)
                .map_err(|e| SaltError::Io(e.to_string()))?;
        }

        let device_state = if paths.device_state_path().exists() {
            let raw = fs::read_to_string(paths.device_state_path())
                .map_err(|e| SaltError::Io(e.to_string()))?;
            serde_json::from_str::<DeviceState>(&raw)
                .map_err(|e| SaltError::Io(format!("Invalid device_state.json: {e}")))?
        } else {
            let state = DeviceState {
                device_id: new_ulid(),
                device_name: device_name.unwrap_or_else(|| "SaltNote-Device".into()),
                last_seq: 0,
                peer_watermarks: BTreeMap::new(),
            };
            let json = serde_json::to_string_pretty(&state)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            fs::write(paths.device_state_path(), json)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            state
        };

        fs::create_dir_all(paths.device_deltas_dir(&device_state.device_id))
            .map_err(|e| SaltError::Io(e.to_string()))?;

        let peer_id = derive_peer_id(&device_state.device_id);
        let (events_tx, _) = broadcast::channel(256);

        let root_loro = LoroDoc::new();
        let _ = root_loro.set_peer_id(peer_id);
        let root_entry = PageEntry {
            doc: root_loro,
            exported_vv: VersionVector::new(),
            dirty_since_export: false,
            markdown_file: None,
        };

        let store = Self {
            paths,
            peer_id,
            device_state: RwLock::new(device_state),
            root_doc: RwLock::new(root_entry),
            pages: RwLock::new(HashMap::new()),
            events_tx,
            sync_revision: AtomicU64::new(0),
            inbox_sync_revision: AtomicU64::new(0),
            flush_lock: Mutex::new(()),
            paired_peers_lock: Mutex::new(()),
        };

        store.replay_all_deltas_from_disk()?;
        store.replay_wal_from_disk()?;
        Ok(store)
    }

    pub fn paths(&self) -> &VaultPaths {
        &self.paths
    }

    pub fn device_state(&self) -> DeviceState {
        self.device_state.read().clone()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<DocChangeEvent> {
        self.events_tx.subscribe()
    }

    pub fn sync_revision(&self) -> u64 {
        self.sync_revision.load(Ordering::Acquire)
    }

    pub fn bump_sync_revision(&self) -> u64 {
        self.sync_revision.fetch_add(1, Ordering::AcqRel) + 1
    }

    pub fn inbox_sync_revision(&self) -> u64 {
        self.inbox_sync_revision.load(Ordering::Acquire)
    }

    pub fn bump_inbox_sync_revision(&self) -> u64 {
        self.inbox_sync_revision.fetch_add(1, Ordering::AcqRel) + 1
    }

    // ------------------------------------------------------------------------
    // Config Load / Save
    // ------------------------------------------------------------------------

    pub fn load_router_config(&self) -> SaltResult<RouterConfig> {
        let raw = fs::read_to_string(self.paths.router_config_path())
            .map_err(|e| SaltError::Io(e.to_string()))?;
        let parsed: RouterConfig =
            serde_json::from_str(&raw).map_err(|e| SaltError::Io(e.to_string()))?;
        let sanitized = sanitize_router_config(&parsed);
        if let Ok(clean_json) = serde_json::to_string_pretty(&sanitized) {
            if clean_json.trim() != raw.trim() {
                let _ = atomic_write(&self.paths.router_config_path(), clean_json.as_bytes());
            }
        }
        Ok(sanitized)
    }

    pub fn save_router_config(&self, cfg: &RouterConfig) -> SaltResult<()> {
        let sanitized = sanitize_router_config(cfg);
        validate_router_config_no_plaintext_keys(&sanitized)?;
        let json =
            serde_json::to_string_pretty(&sanitized).map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.router_config_path(), json.as_bytes())
    }

    pub fn load_halo_presets(&self) -> SaltResult<Vec<HaloPreset>> {
        let raw = fs::read_to_string(self.paths.halo_presets_path())
            .map_err(|e| SaltError::Io(e.to_string()))?;
        let mut presets: Vec<HaloPreset> =
            serde_json::from_str(&raw).map_err(|e| SaltError::Io(e.to_string()))?;
        let latest_defaults = default_halo_presets();
        let mut migrated = false;
        for preset in &mut presets {
            if let Some(latest) = latest_defaults.iter().find(|d| d.preset_id == preset.preset_id) {
                if is_legacy_default_prompt(&preset.preset_id, &preset.system_prompt)
                    && preset.system_prompt != latest.system_prompt
                {
                    preset.system_prompt = latest.system_prompt.clone();
                    migrated = true;
                }
            }
        }
        if migrated {
            if let Ok(json) = serde_json::to_string_pretty(&presets) {
                let _ = atomic_write(&self.paths.halo_presets_path(), json.as_bytes());
            }
        }
        Ok(presets)
    }

    pub fn load_webdav_config(&self) -> SaltResult<WebDavConfig> {
        let raw = fs::read_to_string(self.paths.webdav_config_path())
            .map_err(|e| SaltError::Io(e.to_string()))?;
        serde_json::from_str(&raw).map_err(|e| SaltError::Io(e.to_string()))
    }

    pub fn save_webdav_config(&self, cfg: &WebDavConfig) -> SaltResult<()> {
        if !cfg.password_key_ref.is_empty() && !cfg.password_key_ref.starts_with("vault://") {
            return Err(SaltError::InvalidInput(
                "WebDAV password_key_ref must be a 'vault://' handle".into(),
            ));
        }
        let json =
            serde_json::to_string_pretty(cfg).map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.webdav_config_path(), json.as_bytes())
    }

    // ------------------------------------------------------------------------
    // Local Paired Peers Trust Store & Sync Preferences (`.saltnote/local/`)
    // ------------------------------------------------------------------------

    pub fn load_paired_peers(&self) -> Vec<PairedPeer> {
        let _guard = self.paired_peers_lock.lock();
        self.load_paired_peers_unlocked()
    }

    fn load_paired_peers_unlocked(&self) -> Vec<PairedPeer> {
        let p = self.paths.paired_peers_path();
        if !p.exists() {
            return Vec::new();
        }
        let Ok(raw) = fs::read_to_string(&p) else {
            return Vec::new();
        };
        serde_json::from_str::<Vec<PairedPeer>>(&raw).unwrap_or_default()
    }

    fn save_paired_peers_unlocked(&self, peers: &[PairedPeer]) -> SaltResult<()> {
        let json = serde_json::to_string_pretty(peers).map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.paired_peers_path(), json.as_bytes())
    }

    pub fn is_peer_paired(&self, cert_fingerprint: &str) -> bool {
        let fp = cert_fingerprint.trim().to_ascii_lowercase();
        if fp.is_empty() {
            return false;
        }
        let _guard = self.paired_peers_lock.lock();
        self.load_paired_peers_unlocked()
            .iter()
            .any(|p| p.cert_fingerprint.trim().eq_ignore_ascii_case(&fp))
    }

    pub fn upsert_paired_peer(&self, mut peer: PairedPeer) -> SaltResult<()> {
        peer.cert_fingerprint = peer.cert_fingerprint.trim().to_ascii_lowercase();
        let _guard = self.paired_peers_lock.lock();
        let mut peers = self.load_paired_peers_unlocked();
        if let Some(existing) = peers.iter_mut().find(|p| {
            p.device_id == peer.device_id
                || (!peer.cert_fingerprint.is_empty()
                    && p.cert_fingerprint.eq_ignore_ascii_case(&peer.cert_fingerprint))
        }) {
            existing.device_id = peer.device_id;
            existing.device_name = peer.device_name;
            existing.cert_fingerprint = peer.cert_fingerprint;
            existing.last_host = peer.last_host;
            existing.last_port = peer.last_port;
            if peer.paired_at_utc > 0 {
                existing.paired_at_utc = peer.paired_at_utc;
            }
            if peer.last_sync_utc > existing.last_sync_utc {
                existing.last_sync_utc = peer.last_sync_utc;
            }
            existing.auto_sync = peer.auto_sync;
        } else {
            peers.push(peer);
        }
        peers.sort_by(|a, b| a.device_name.cmp(&b.device_name).then(a.device_id.cmp(&b.device_id)));
        self.save_paired_peers_unlocked(&peers)
    }

    pub fn remove_paired_peer(&self, device_id: &str) -> SaltResult<()> {
        let _guard = self.paired_peers_lock.lock();
        let mut peers = self.load_paired_peers_unlocked();
        peers.retain(|p| p.device_id != device_id);
        self.save_paired_peers_unlocked(&peers)
    }

    pub fn set_paired_peer_auto_sync(&self, device_id: &str, enabled: bool) -> SaltResult<()> {
        let _guard = self.paired_peers_lock.lock();
        let mut peers = self.load_paired_peers_unlocked();
        if let Some(p) = peers.iter_mut().find(|p| p.device_id == device_id) {
            p.auto_sync = enabled;
            return self.save_paired_peers_unlocked(&peers);
        }
        Err(SaltError::InvalidInput(format!("Paired peer {device_id} not found")))
    }

    pub fn update_paired_peer_endpoint(
        &self,
        device_id: &str,
        host: &str,
        port: u16,
        device_name: Option<&str>,
    ) -> SaltResult<()> {
        let _guard = self.paired_peers_lock.lock();
        let mut peers = self.load_paired_peers_unlocked();
        if let Some(p) = peers.iter_mut().find(|p| p.device_id == device_id) {
            let clean_name = device_name.map(str::trim).filter(|s| !s.is_empty());
            let changed = p.last_host != host
                || p.last_port != port
                || clean_name.map(|n| p.device_name != n).unwrap_or(false);
            if changed {
                p.last_host = host.to_string();
                p.last_port = port;
                if let Some(n) = clean_name {
                    p.device_name = n.to_string();
                }
                self.save_paired_peers_unlocked(&peers)?;
            }
        }
        Ok(())
    }

    pub fn touch_paired_peer_sync(&self, cert_fingerprint: &str, sync_utc_ms: i64) -> SaltResult<()> {
        let fp = cert_fingerprint.trim().to_ascii_lowercase();
        if fp.is_empty() {
            return Ok(());
        }
        let _guard = self.paired_peers_lock.lock();
        let mut peers = self.load_paired_peers_unlocked();
        if let Some(p) = peers.iter_mut().find(|p| p.cert_fingerprint.eq_ignore_ascii_case(&fp)) {
            if sync_utc_ms > p.last_sync_utc {
                p.last_sync_utc = sync_utc_ms;
                self.save_paired_peers_unlocked(&peers)?;
            }
        }
        Ok(())
    }

    pub fn load_sync_prefs(&self) -> LocalSyncPrefs {
        let p = self.paths.sync_prefs_path();
        if !p.exists() {
            return LocalSyncPrefs::default();
        }
        fs::read_to_string(&p)
            .ok()
            .and_then(|raw| serde_json::from_str::<LocalSyncPrefs>(&raw).ok())
            .unwrap_or_default()
    }

    pub fn save_sync_prefs(&self, prefs: &LocalSyncPrefs) -> SaltResult<()> {
        let json = serde_json::to_string_pretty(prefs).map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.sync_prefs_path(), json.as_bytes())
    }

    // ------------------------------------------------------------------------
    // Page CRUD & Dual-Mode Mutations
    // ------------------------------------------------------------------------

    pub fn create_page(
        &self,
        title: impl Into<String>,
        mode: PageMode,
        is_private: bool,
        tags: Vec<String>,
        initial_markdown: impl Into<String>,
    ) -> SaltResult<PageMeta> {
        let page_id = new_ulid();
        let now = now_utc_ms();
        let meta = PageMeta {
            page_id: page_id.clone(),
            title: title.into(),
            mode,
            is_private,
            tags,
            created_at_utc: now,
            updated_at_utc: now,
            is_deleted: false,
        };
        let initial_body = initial_markdown.into();

        let doc = self.new_loro_doc();
        let pre_vv = doc.oplog_vv();
        write_meta_to_doc(&doc, &meta)?;
        if !initial_body.is_empty() {
            let body = doc.get_text("body");
            body.insert(0, &initial_body)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        doc.commit();

        let update_bytes = doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(&page_id, &update_bytes)?;

        let md_file = self.project_markdown_file(&meta, &initial_body, None)?;
        {
            let mut pages = self.pages.write();
            pages.insert(
                page_id.clone(),
                PageEntry {
                    doc,
                    exported_vv: VersionVector::new(),
                    dirty_since_export: true,
                    markdown_file: Some(md_file),
                },
            );
        }

        let _ = self.events_tx.send(DocChangeEvent::PageUpdated {
            page_id,
            meta: meta.clone(),
            body_markdown: initial_body,
        });
        Ok(meta)
    }

    /// Ensures a welcoming offline starter page exists for first-launch zero-config UX.
    pub fn ensure_welcome_page(&self) -> SaltResult<PageMeta> {
        let existing = self.list_pages();
        if let Some(first) = existing.first() {
            return Ok(first.meta.clone());
        }
        let body = r#"# 欢迎使用 SaltNote Alpha

SaltNote 是一款**永久免费开源、本地优先（Local-First）、零后端依赖**的双模手账与学习笔记应用。

## 快速上手

1. **双模记录**：点击顶部切换 **画布模式** 与 **文档模式**，支持压感笔迹、荧光笔、橡皮擦、图片插入与行内嵌入画布块。
2. **随手圈（Salt Halo）**：
   - 🟡 **琥珀黄**：圈选重点，自动概念扩充与类比。
   - 🔵 **冰川蓝**：一键提炼 1–3 张 FSRS 闪卡入库复习。
   - ⚪ **矿石灰**：截取高清 WebP 局部切片，将手写板书清洗为整洁 Markdown。
3. **隐私底线**：点击右上角 `#private` 锁标，即可硬阻断该页向任何公网 AI 发包，仅允许本机或局域网 Ollama。

```salt-canvas
{"canvas_id":"inline-welcome","height":220}
```
"#;
        self.create_page(
            "欢迎使用 SaltNote",
            PageMode::Study,
            false,
            vec!["引导".into(), "SaltNote".into()],
            body,
        )
    }

    pub fn update_page_meta(
        &self,
        page_id: &str,
        title: Option<String>,
        mode: Option<PageMode>,
        is_private: Option<bool>,
        tags: Option<Vec<String>>,
    ) -> SaltResult<PageMeta> {
        let (meta, body_str, old_file) = {
            let mut pages = self.pages.write();
            let entry = pages
                .get_mut(page_id)
                .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
            let mut meta = read_meta_from_doc(&entry.doc, page_id)?;
            if let Some(t) = title {
                meta.title = t;
            }
            if let Some(m) = mode {
                meta.mode = m;
            }
            if let Some(p) = is_private {
                meta.is_private = p;
            }
            if let Some(tg) = tags {
                meta.tags = tg;
            }
            meta.updated_at_utc = now_utc_ms();

            let pre_vv = entry.doc.oplog_vv();
            write_meta_to_doc(&entry.doc, &meta)?;
            entry.doc.commit();
            let bytes = entry
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(page_id, &bytes)?;
            entry.dirty_since_export = true;
            let body_str = entry.doc.get_text("body").to_string();
            (meta, body_str, entry.markdown_file.clone())
        };

        let new_file = self.project_markdown_file(&meta, &body_str, old_file.as_deref())?;
        if let Some(entry) = self.pages.write().get_mut(page_id) {
            entry.markdown_file = Some(new_file);
        }
        let _ = self.events_tx.send(DocChangeEvent::PageUpdated {
            page_id: page_id.to_string(),
            meta: meta.clone(),
            body_markdown: body_str,
        });
        Ok(meta)
    }

    pub fn update_page_body(&self, page_id: &str, new_markdown: &str) -> SaltResult<()> {
        let (meta, body_str, existing_file) = {
            let mut pages = self.pages.write();
            let entry = pages
                .get_mut(page_id)
                .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
            let pre_vv = entry.doc.oplog_vv();
            let body = entry.doc.get_text("body");
            body.update(new_markdown, UpdateOptions::default())
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            let mut meta = read_meta_from_doc(&entry.doc, page_id)?;
            meta.updated_at_utc = now_utc_ms();
            write_meta_to_doc(&entry.doc, &meta)?;
            entry.doc.commit();

            let bytes = entry
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(page_id, &bytes)?;
            entry.dirty_since_export = true;
            (meta, body.to_string(), entry.markdown_file.clone())
        };

        let md_file = self.project_markdown_file(&meta, &body_str, existing_file.as_deref())?;
        if let Some(entry) = self.pages.write().get_mut(page_id) {
            entry.markdown_file = Some(md_file);
        }
        let _ = self.events_tx.send(DocChangeEvent::PageUpdated {
            page_id: page_id.to_string(),
            meta,
            body_markdown: body_str,
        });
        Ok(())
    }

    /// Tombstone-deletes a page inside Loro CRDT so the deletion propagates cleanly across
    /// WebDAV and LAN P2P peers without resurrecting deleted notes.
    pub fn delete_page(&self, page_id: &str) -> SaltResult<()> {
        let (meta, old_file) = {
            let mut pages = self.pages.write();
            let entry = pages
                .get_mut(page_id)
                .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
            let mut meta = read_meta_from_doc(&entry.doc, page_id)?;
            meta.is_deleted = true;
            meta.updated_at_utc = now_utc_ms();

            let pre_vv = entry.doc.oplog_vv();
            write_meta_to_doc(&entry.doc, &meta)?;
            entry.doc.commit();

            let bytes = entry
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(page_id, &bytes)?;
            entry.dirty_since_export = true;
            let prev = entry.markdown_file.take();
            (meta, prev)
        };

        if let Some(prev) = old_file {
            if !prev.is_empty() {
                let _ = fs::remove_file(self.paths.pages_dir().join(prev));
            }
        }
        let fallback_md = format_markdown_filename(&meta);
        let _ = fs::remove_file(self.paths.pages_dir().join(fallback_md));

        let _ = self.events_tx.send(DocChangeEvent::PageDeleted {
            page_id: page_id.to_string(),
        });
        Ok(())
    }

    /// Appends a stroke into `canvas[canvas_id].strokes` and writes crash-safe WAL.
    pub fn add_stroke(
        &self,
        page_id: &str,
        canvas_id: &str,
        stroke: &DeltaStroke,
    ) -> SaltResult<()> {
        let mut pages = self.pages.write();
        let entry = pages
            .get_mut(page_id)
            .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
        let pre_vv = entry.doc.oplog_vv();

        let canvases = entry.doc.get_map("canvas");
        let c_map = canvases
            .ensure_mergeable_map(canvas_id)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let strokes_map = c_map
            .ensure_mergeable_map("strokes")
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let encoded =
            serde_json::to_string(stroke).map_err(|e| SaltError::Crdt(e.to_string()))?;
        strokes_map
            .insert(&stroke.stroke_id, encoded)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;

        touch_updated_at(&entry.doc)?;
        entry.doc.commit();

        let bytes = entry
            .doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(page_id, &bytes)?;
        entry.dirty_since_export = true;
        Ok(())
    }

    /// Applies transform or tombstone delete to a stroke inside `canvas[canvas_id].transforms`.
    pub fn upsert_stroke_transform(
        &self,
        page_id: &str,
        canvas_id: &str,
        transform: &StrokeTransform,
    ) -> SaltResult<()> {
        let mut pages = self.pages.write();
        let entry = pages
            .get_mut(page_id)
            .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
        let pre_vv = entry.doc.oplog_vv();

        let canvases = entry.doc.get_map("canvas");
        let c_map = canvases
            .ensure_mergeable_map(canvas_id)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let tr_map = c_map
            .ensure_mergeable_map("transforms")
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let encoded =
            serde_json::to_string(transform).map_err(|e| SaltError::Crdt(e.to_string()))?;
        tr_map
            .insert(&transform.stroke_id, encoded)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;

        touch_updated_at(&entry.doc)?;
        entry.doc.commit();

        let bytes = entry
            .doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(page_id, &bytes)?;
        entry.dirty_since_export = true;
        Ok(())
    }

    pub fn upsert_canvas_image(
        &self,
        page_id: &str,
        canvas_id: &str,
        node: &CanvasImageNode,
    ) -> SaltResult<()> {
        let mut pages = self.pages.write();
        let entry = pages
            .get_mut(page_id)
            .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
        let pre_vv = entry.doc.oplog_vv();

        let canvases = entry.doc.get_map("canvas");
        let c_map = canvases
            .ensure_mergeable_map(canvas_id)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let img_map = c_map
            .ensure_mergeable_map("images")
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let encoded = serde_json::to_string(node).map_err(|e| SaltError::Crdt(e.to_string()))?;
        img_map
            .insert(&node.image_id, encoded)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;

        touch_updated_at(&entry.doc)?;
        entry.doc.commit();

        let bytes = entry
            .doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(page_id, &bytes)?;
        entry.dirty_since_export = true;
        Ok(())
    }

    /// Updates position/size or tombstone delete state of an existing `CanvasImageNode`.
    pub fn update_canvas_image_transform(
        &self,
        page_id: &str,
        canvas_id: &str,
        image_id: &str,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        deleted: bool,
    ) -> SaltResult<CanvasImageNode> {
        let snap = self.get_page_snapshot(page_id)?;
        let mut node = snap
            .canvases
            .into_iter()
            .find(|c| c.canvas_id == canvas_id)
            .and_then(|c| c.images.into_iter().find(|img| img.image_id == image_id))
            .ok_or_else(|| {
                SaltError::InvalidInput(format!("Image {image_id} not found on canvas {canvas_id}"))
            })?;
        node.x = x;
        node.y = y;
        node.width = width.max(24.0);
        node.height = height.max(24.0);
        node.deleted = deleted;
        node.updated_at_utc = now_utc_ms();
        self.upsert_canvas_image(page_id, canvas_id, &node)?;
        Ok(node)
    }

    /// Validates that an asset relative path strictly targets `assets/<normal_segments>` without traversal.
    pub fn asset_abs_path_checked(&self, rel_path: &str) -> SaltResult<PathBuf> {
        validate_safe_asset_rel_path(rel_path)?;
        Ok(self.paths.root.join(rel_path))
    }

    /// Writes immutable WebP/Opus asset into `assets/{sha[:2]}/{sha[:16]}.ext` if not present.
    pub fn store_asset_bytes(&self, rel_path: &str, bytes: &[u8]) -> SaltResult<PathBuf> {
        let abs = self.asset_abs_path_checked(rel_path)?;
        if let Some(parent) = abs.parent() {
            fs::create_dir_all(parent).map_err(|e| SaltError::Io(e.to_string()))?;
        }
        if !abs.exists() {
            atomic_write(&abs, bytes)?;
        }
        Ok(abs)
    }

    /// Atomically commits a streamed temporary file into a validated `assets/...` path.
    pub fn commit_temp_asset_file(&self, tmp_path: &Path, rel_path: &str) -> SaltResult<PathBuf> {
        let abs = self.asset_abs_path_checked(rel_path)?;
        if abs.exists() {
            let _ = fs::remove_file(tmp_path);
            return Ok(abs);
        }
        if let Some(parent) = abs.parent() {
            fs::create_dir_all(parent).map_err(|e| SaltError::Io(e.to_string()))?;
        }
        if fs::rename(tmp_path, &abs).is_err() {
            // Fallback across volumes or brief Windows file locks
            let copy_res = fs::copy(tmp_path, &abs);
            let _ = fs::remove_file(tmp_path);
            copy_res.map_err(|e| SaltError::Io(e.to_string()))?;
        }
        Ok(abs)
    }

    pub fn upsert_halo(&self, halo: &HaloAnnotation) -> SaltResult<()> {
        let mut pages = self.pages.write();
        let entry = pages
            .get_mut(&halo.page_id)
            .ok_or_else(|| SaltError::PageNotFound(halo.page_id.clone()))?;
        let pre_vv = entry.doc.oplog_vv();

        let halos = entry.doc.get_map("halos");
        let encoded = serde_json::to_string(halo).map_err(|e| SaltError::Crdt(e.to_string()))?;
        halos
            .insert(&halo.halo_id, encoded)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        touch_updated_at(&entry.doc)?;
        entry.doc.commit();

        let bytes = entry
            .doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(&halo.page_id, &bytes)?;
        entry.dirty_since_export = true;
        Ok(())
    }

    /// Updates a Salt Halo pin's movable world-space coordinates in CRDT.
    pub fn update_halo_pin(
        &self,
        page_id: &str,
        halo_id: &str,
        pin_x: f32,
        pin_y: f32,
    ) -> SaltResult<HaloAnnotation> {
        let snap = self.get_page_snapshot(page_id)?;
        let mut halo = snap
            .halos
            .into_iter()
            .find(|h| h.halo_id == halo_id)
            .ok_or_else(|| SaltError::InvalidInput(format!("Halo {halo_id} not found")))?;
        halo.pin_x = Some(pin_x);
        halo.pin_y = Some(pin_y);
        self.upsert_halo(&halo)?;
        Ok(halo)
    }

    /// Removes a Salt Halo annotation from the page's CRDT `halos` map so the deletion persists and syncs across devices.
    pub fn delete_halo(&self, page_id: &str, halo_id: &str) -> SaltResult<()> {
        let mut pages = self.pages.write();
        let entry = pages
            .get_mut(page_id)
            .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
        let pre_vv = entry.doc.oplog_vv();

        let halos = entry.doc.get_map("halos");
        halos
            .delete(halo_id)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        touch_updated_at(&entry.doc)?;
        entry.doc.commit();

        let bytes = entry
            .doc
            .export(ExportMode::updates(&pre_vv))
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        self.append_wal(page_id, &bytes)?;
        entry.dirty_since_export = true;
        Ok(())
    }

    /// Fuses a Salt Halo crystal's answer into the page body using semantic Callout syntax
    /// (converting glacier flashcard JSON into clean readable Markdown Q/A blocks) and marks the halo fused.
    pub fn fuse_halo_into_body(&self, page_id: &str, halo_id: &str) -> SaltResult<String> {
        let snap = self.get_page_snapshot(page_id)?;
        let mut halo = snap
            .halos
            .into_iter()
            .find(|h| h.halo_id == halo_id)
            .ok_or_else(|| SaltError::InvalidInput(format!("Halo {halo_id} not found")))?;

        let header = match halo.preset_id.as_str() {
            "builtin.amber_expand" => "[!salt-amber] 琥珀黄 · 概念扩充".to_string(),
            "builtin.glacier_card" => "[!salt-glacier] 冰川蓝 · 提炼闪卡".to_string(),
            "builtin.mineral_ocr" => "[!salt-mineral] 矿石灰 · 笔迹清洗 OCR".to_string(),
            other => format!("[!salt-amber] Salt Halo ({other})"),
        };

        let formatted_content = if halo.preset_id == "builtin.glacier_card" {
            let drafts = parse_flashcard_drafts(&halo.response_markdown);
            if !drafts.is_empty() {
                drafts
                    .iter()
                    .enumerate()
                    .map(|(i, c)| {
                        format!(
                            "**Q{}**：{}\n\n**A{}**：{}",
                            i + 1,
                            c.front.trim(),
                            i + 1,
                            c.back.trim()
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n\n---\n\n")
            } else {
                halo.response_markdown.trim().to_string()
            }
        } else {
            halo.response_markdown.trim().to_string()
        };

        let quoted_body = formatted_content
            .lines()
            .map(|l| {
                if l.trim().is_empty() {
                    ">".to_string()
                } else {
                    format!("> {l}")
                }
            })
            .collect::<Vec<_>>()
            .join("\n");

        let fused_block = format!("\n\n> {header}\n>\n{quoted_body}\n");
        let updated_body = format!("{}{}", snap.body_markdown.trim_end(), fused_block);
        self.update_page_body(page_id, &updated_body)?;

        halo.fused = true;
        halo.collapsed = true;
        self.upsert_halo(&halo)?;
        Ok(updated_body)
    }

    pub fn upsert_card(&self, card: &FlashCard) -> SaltResult<()> {
        let (cards, logs) = {
            let mut pages = self.pages.write();
            let entry = pages
                .get_mut(&card.page_id)
                .ok_or_else(|| SaltError::PageNotFound(card.page_id.clone()))?;
            let pre_vv = entry.doc.oplog_vv();

            let cards_map = entry.doc.get_map("cards");
            let encoded =
                serde_json::to_string(card).map_err(|e| SaltError::Crdt(e.to_string()))?;
            cards_map
                .insert(&card.card_id, encoded)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            touch_updated_at(&entry.doc)?;
            entry.doc.commit();

            let bytes = entry
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(&card.page_id, &bytes)?;
            entry.dirty_since_export = true;
            (
                read_cards_from_doc(&entry.doc),
                read_review_logs_from_doc(&entry.doc),
            )
        };
        let _ = self.events_tx.send(DocChangeEvent::CardsUpdated {
            page_id: card.page_id.clone(),
            cards,
            review_logs: logs,
        });
        Ok(())
    }

    /// Updates front/back text of an existing flashcard while preserving its `card_id`, anchor, and FSRS review logs.
    pub fn update_card(
        &self,
        page_id: &str,
        card_id: &str,
        front: impl Into<String>,
        back: impl Into<String>,
    ) -> SaltResult<FlashCard> {
        let snap = self.get_page_snapshot(page_id)?;
        let mut card = snap
            .cards
            .into_iter()
            .find(|c| c.card_id == card_id)
            .ok_or_else(|| SaltError::InvalidInput(format!("Flashcard {card_id} not found")))?;
        card.front = front.into();
        card.back = back.into();
        self.upsert_card(&card)?;
        Ok(card)
    }

    /// Tombstone-deletes (archives) a flashcard inside CRDT so the deletion syncs across devices.
    pub fn delete_card(&self, page_id: &str, card_id: &str) -> SaltResult<()> {
        let snap = self.get_page_snapshot(page_id)?;
        let mut card = snap
            .cards
            .into_iter()
            .find(|c| c.card_id == card_id)
            .ok_or_else(|| SaltError::InvalidInput(format!("Flashcard {card_id} not found")))?;
        card.archived = true;
        self.upsert_card(&card)?;
        Ok(())
    }

    pub fn append_review_log(&self, page_id: &str, log: &ReviewLog) -> SaltResult<()> {
        let (cards, logs) = {
            let mut pages = self.pages.write();
            let entry = pages
                .get_mut(page_id)
                .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
            let pre_vv = entry.doc.oplog_vv();

            let list: LoroList = entry.doc.get_list("fsrs_logs");
            let encoded =
                serde_json::to_string(log).map_err(|e| SaltError::Crdt(e.to_string()))?;
            list.push(encoded)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            touch_updated_at(&entry.doc)?;
            entry.doc.commit();

            let bytes = entry
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(page_id, &bytes)?;
            entry.dirty_since_export = true;
            (
                read_cards_from_doc(&entry.doc),
                read_review_logs_from_doc(&entry.doc),
            )
        };
        let _ = self.events_tx.send(DocChangeEvent::CardsUpdated {
            page_id: page_id.to_string(),
            cards,
            review_logs: logs,
        });
        Ok(())
    }

    // ------------------------------------------------------------------------
    // Salt Inbox (盐池) CRDT Root Container Mutations & Queries (v0.2-alpha)
    // ------------------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn capture_inbox_item(
        &self,
        kind: InboxKind,
        text_note: Option<String>,
        primary_media_bytes: Option<&[u8]>,
        primary_media_ext: Option<&str>,
        thumb_webp_bytes: Option<&[u8]>,
        audio_duration_ms: u32,
        is_private: bool,
    ) -> SaltResult<InboxItem> {
        let mut payload = InboxPayload {
            text_note: text_note.and_then(|s| {
                let t = s.trim().to_string();
                if t.is_empty() { None } else { Some(t) }
            }),
            audio_duration_ms,
            ..Default::default()
        };

        if let Some(bytes) = primary_media_bytes.filter(|b| !b.is_empty()) {
            let fallback_ext = match kind {
                InboxKind::VoiceMemo => "ogg",
                InboxKind::PhotoNote => "webp",
                _ => "bin",
            };
            let ext = primary_media_ext.unwrap_or(fallback_ext);
            let (hash, rel_path) = content_addressed_asset_rel_path(bytes, ext);
            self.store_asset_bytes(&rel_path, bytes)?;
            match kind {
                InboxKind::VoiceMemo => {
                    payload.audio_asset_hash = Some(format!("sha256:{}", &hash[..16]));
                    payload.audio_asset_rel_path = Some(rel_path);
                }
                _ => {
                    payload.image_asset_hash = Some(format!("sha256:{}", &hash[..16]));
                    payload.image_asset_rel_path = Some(rel_path);
                }
            }
        }

        if let Some(tbytes) = thumb_webp_bytes.filter(|b| !b.is_empty()) {
            let (hash, rel_path) = content_addressed_asset_rel_path(tbytes, "webp");
            self.store_asset_bytes(&rel_path, tbytes)?;
            payload.thumb_asset_hash = Some(format!("sha256:{}", &hash[..16]));
            payload.thumb_asset_rel_path = Some(rel_path);
        }

        let initial_status = match kind {
            InboxKind::VoiceMemo => {
                if is_private {
                    TranscriptStatus::WaitingForLanRelay
                } else {
                    TranscriptStatus::Pending
                }
            }
            InboxKind::PhotoNote => {
                if payload.image_asset_rel_path.is_some() {
                    if is_private {
                        TranscriptStatus::WaitingForLanRelay
                    } else {
                        TranscriptStatus::Pending
                    }
                } else {
                    TranscriptStatus::NotApplicable
                }
            }
            InboxKind::QuickText | InboxKind::WebClip => TranscriptStatus::NotApplicable,
        };

        let item = InboxItem {
            inbox_id: format!("inbx_{}", new_ulid()),
            kind,
            created_at_utc: now_utc_ms(),
            source_device_id: self.device_state.read().device_id.clone(),
            is_private,
            state: InboxState::Unprocessed,
            payload,
            ai_enrichment: InboxAiEnrichment {
                transcript_status: initial_status,
                transcript_text: None,
                summary_text: None,
                auto_tags: Vec::new(),
            },
            consumption: InboxConsumption::default(),
        };

        self.upsert_inbox_item(&item)?;
        Ok(item)
    }

    pub fn upsert_inbox_item(&self, item: &InboxItem) -> SaltResult<()> {
        {
            let mut root = self.root_doc.write();
            let pre_vv = root.doc.oplog_vv();
            let v2 = root.doc.get_map("inbox_v2");
            let item_map = v2
                .ensure_mergeable_map(&item.inbox_id)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            let base = InboxItemBase {
                inbox_id: item.inbox_id.clone(),
                kind: item.kind,
                created_at_utc: item.created_at_utc,
                source_device_id: item.source_device_id.clone(),
                payload: item.payload.clone(),
            };
            let base_json =
                serde_json::to_string(&base).map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("base", base_json)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("is_private", item.is_private)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert(
                    "transcript_status",
                    item.ai_enrichment.transcript_status.as_str(),
                )
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            if let Some(txt) = item.ai_enrichment.transcript_text.as_deref() {
                item_map
                    .insert("transcript_text", txt)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(sum) = item.ai_enrichment.summary_text.as_deref() {
                item_map
                    .insert("summary_text", sum)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if !item.ai_enrichment.auto_tags.is_empty() {
                let tags_json = serde_json::to_string(&item.ai_enrichment.auto_tags)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
                item_map
                    .insert("auto_tags", tags_json)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if item.state == InboxState::Archived {
                item_map
                    .insert("archived", true)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            } else if item.state == InboxState::Deleted {
                item_map
                    .insert("deleted", true)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(pid) = item.consumption.consumed_page_id.as_deref() {
                item_map
                    .insert("consumed_page_id", pid)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(bid) = item.consumption.consumed_block_id.as_deref() {
                item_map
                    .insert("consumed_block_id", bid)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(ts) = item.consumption.consumed_at_utc {
                item_map
                    .insert("consumed_at_utc", ts)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            root.doc.commit();

            let bytes = root
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(VAULT_ROOT_DOC_ID, &bytes)?;
            root.dirty_since_export = true;
        }
        let _ = self.events_tx.send(DocChangeEvent::InboxUpdated);
        Ok(())
    }

    fn mutate_inbox_v2<F>(&self, inbox_id: &str, f: F) -> SaltResult<InboxItem>
    where
        F: FnOnce(&LoroMap) -> SaltResult<()>,
    {
        {
            let mut root = self.root_doc.write();
            let item_map = ensure_inbox_v2_item_map(&root.doc, inbox_id)?;
            let pre_vv = root.doc.oplog_vv();
            f(&item_map)?;
            root.doc.commit();

            let bytes = root
                .doc
                .export(ExportMode::updates(&pre_vv))
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            self.append_wal(VAULT_ROOT_DOC_ID, &bytes)?;
            root.dirty_since_export = true;
        }
        let _ = self.events_tx.send(DocChangeEvent::InboxUpdated);
        self.get_inbox_item(inbox_id)
    }

    pub fn get_inbox_item(&self, inbox_id: &str) -> SaltResult<InboxItem> {
        let root = self.root_doc.read();
        read_inbox_from_doc(&root.doc)
            .into_iter()
            .find(|it| it.inbox_id == inbox_id)
            .ok_or_else(|| SaltError::InvalidInput(format!("Inbox item {inbox_id} not found")))
    }

    pub fn list_inbox_items(&self, include_archived: bool, limit: usize) -> Vec<InboxItem> {
        let root = self.root_doc.read();
        let mut items: Vec<InboxItem> = read_inbox_from_doc(&root.doc)
            .into_iter()
            .filter(|it| match it.state {
                InboxState::Unprocessed => true,
                InboxState::Archived => include_archived,
                InboxState::Deleted => false,
            })
            .collect();
        items.sort_by(|a, b| {
            b.created_at_utc
                .cmp(&a.created_at_utc)
                .then(b.inbox_id.cmp(&a.inbox_id))
        });
        if limit > 0 && items.len() > limit {
            items.truncate(limit);
        }
        items
    }

    pub fn update_inbox_enrichment(
        &self,
        inbox_id: &str,
        status: TranscriptStatus,
        transcript_text: Option<String>,
        summary_text: Option<String>,
        auto_tags: Option<Vec<String>>,
    ) -> SaltResult<InboxItem> {
        self.mutate_inbox_v2(inbox_id, |item_map| {
            item_map
                .insert("transcript_status", status.as_str())
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            if let Some(txt) = transcript_text {
                item_map
                    .insert("transcript_text", txt)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(sum) = summary_text {
                item_map
                    .insert("summary_text", sum)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            if let Some(tags) = auto_tags {
                let tags_json =
                    serde_json::to_string(&tags).map_err(|e| SaltError::Crdt(e.to_string()))?;
                item_map
                    .insert("auto_tags", tags_json)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            Ok(())
        })
    }

    pub fn set_inbox_private(&self, inbox_id: &str, is_private: bool) -> SaltResult<InboxItem> {
        let current = self.get_inbox_item(inbox_id)?;
        let next_status = if is_private
            && current.ai_enrichment.transcript_status == TranscriptStatus::Pending
        {
            Some(TranscriptStatus::WaitingForLanRelay)
        } else if !is_private
            && current.ai_enrichment.transcript_status == TranscriptStatus::WaitingForLanRelay
        {
            Some(TranscriptStatus::Pending)
        } else {
            None
        };
        self.mutate_inbox_v2(inbox_id, |item_map| {
            item_map
                .insert("is_private", is_private)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            if let Some(st) = next_status {
                item_map
                    .insert("transcript_status", st.as_str())
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
            Ok(())
        })
    }

    pub fn delete_inbox_item(&self, inbox_id: &str) -> SaltResult<()> {
        let _ = self.mutate_inbox_v2(inbox_id, |item_map| {
            item_map
                .insert("deleted", true)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            Ok(())
        })?;
        Ok(())
    }

    pub fn consume_inbox_into_page(
        &self,
        inbox_id: &str,
        target_page_id: &str,
        target_block_id: &str,
    ) -> SaltResult<InboxItem> {
        {
            let pages = self.pages.read();
            let entry = pages
                .get(target_page_id)
                .ok_or_else(|| SaltError::PageNotFound(target_page_id.to_string()))?;
            let meta = read_meta_from_doc(&entry.doc, target_page_id)?;
            if meta.is_deleted {
                return Err(SaltError::PageNotFound(target_page_id.to_string()));
            }
        }
        let consumed_at = now_utc_ms();
        self.mutate_inbox_v2(inbox_id, |item_map| {
            item_map
                .insert("archived", true)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("consumed_page_id", target_page_id)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("consumed_block_id", target_block_id)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("consumed_at_utc", consumed_at)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            Ok(())
        })
    }

    /// Collects all asset relative paths referenced by active pages, Halo slices, and Inbox items,
    /// returning `(rel_path, is_lightweight_mobile_priority)`.
    pub fn collect_referenced_assets(&self) -> Vec<(String, bool)> {
        let mut map: BTreeMap<String, bool> = BTreeMap::new();
        {
            let pages = self.pages.read();
            for (page_id, entry) in pages.iter() {
                if let Ok(meta) = read_meta_from_doc(&entry.doc, page_id) {
                    if meta.is_deleted {
                        continue;
                    }
                }
                for c in read_canvases_from_doc(&entry.doc) {
                    for img in c.images {
                        if !img.deleted && !img.asset_rel_path.is_empty() {
                            map.entry(img.asset_rel_path).or_insert(false);
                        }
                    }
                }
                for h in read_halos_from_doc(&entry.doc) {
                    if let Some(rel) = h.slice_asset_rel_path {
                        if !rel.is_empty() {
                            map.insert(rel, true);
                        }
                    }
                }
            }
        }
        {
            let root = self.root_doc.read();
            for item in read_inbox_from_doc(&root.doc) {
                if item.state == InboxState::Deleted {
                    continue;
                }
                if let Some(rel) = item.payload.thumb_asset_rel_path {
                    if !rel.is_empty() {
                        map.insert(rel, true);
                    }
                }
                if let Some(rel) = item.payload.audio_asset_rel_path {
                    if !rel.is_empty() {
                        map.insert(rel, true);
                    }
                }
                if let Some(rel) = item.payload.image_asset_rel_path {
                    if !rel.is_empty() {
                        map.entry(rel).or_insert(false);
                    }
                }
            }
        }
        map.into_iter().collect()
    }

    /// Computes canonical SHA-256 hash over normalized Vault-level Inbox CRDT state for tests.
    pub fn compute_inbox_state_hash(&self) -> SaltResult<String> {
        let root = self.root_doc.read();
        let mut items = read_inbox_from_doc(&root.doc);
        items.sort_by(|a, b| a.inbox_id.cmp(&b.inbox_id));
        let bytes = serde_json::to_vec(&items).map_err(|e| SaltError::Crdt(e.to_string()))?;
        Ok(sha256_hex(&bytes))
    }

    // ------------------------------------------------------------------------
    // Reads & Queries
    // ------------------------------------------------------------------------

    pub fn list_pages(&self) -> Vec<PageSummary> {
        let pages = self.pages.read();
        let mut out = Vec::with_capacity(pages.len());
        for (page_id, entry) in pages.iter() {
            if let Ok(meta) = read_meta_from_doc(&entry.doc, page_id) {
                if meta.is_deleted {
                    continue;
                }
                let body = entry.doc.get_text("body").to_string();
                let snippet: String = body
                    .lines()
                    .map(str::trim)
                    .filter(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with("```"))
                    .take(2)
                    .collect::<Vec<_>>()
                    .join(" ");
                let canvases = read_canvases_from_doc(&entry.doc);
                let stroke_count: u32 = canvases
                    .iter()
                    .map(|c| {
                        let deleted: std::collections::HashSet<&str> = c
                            .transforms
                            .iter()
                            .filter(|t| t.deleted)
                            .map(|t| t.stroke_id.as_str())
                            .collect();
                        c.strokes
                            .iter()
                            .filter(|s| !deleted.contains(s.stroke_id.as_str()))
                            .count() as u32
                    })
                    .sum();
                let card_count = read_cards_from_doc(&entry.doc)
                    .iter()
                    .filter(|c| !c.archived)
                    .count() as u32;
                out.push(PageSummary {
                    meta,
                    snippet: snippet.chars().take(120).collect(),
                    stroke_count,
                    card_count,
                    markdown_file: entry.markdown_file.clone().unwrap_or_default(),
                });
            }
        }
        out.sort_by(|a, b| b.meta.updated_at_utc.cmp(&a.meta.updated_at_utc));
        out
    }

    pub fn get_page_snapshot(&self, page_id: &str) -> SaltResult<PageSnapshot> {
        let pages = self.pages.read();
        let entry = pages
            .get(page_id)
            .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
        let meta = read_meta_from_doc(&entry.doc, page_id)?;
        let body_markdown = entry.doc.get_text("body").to_string();
        let canvases = read_canvases_from_doc(&entry.doc);
        let halos = read_halos_from_doc(&entry.doc);
        let cards = read_cards_from_doc(&entry.doc);
        Ok(PageSnapshot {
            meta,
            body_markdown,
            canvases,
            halos,
            cards,
        })
    }

    pub fn get_all_cards_and_logs(&self) -> (Vec<FlashCard>, Vec<ReviewLog>) {
        let pages = self.pages.read();
        let mut all_cards = Vec::new();
        let mut all_logs = Vec::new();
        for (page_id, entry) in pages.iter() {
            if let Ok(meta) = read_meta_from_doc(&entry.doc, page_id) {
                if meta.is_deleted {
                    continue;
                }
            }
            all_cards.extend(read_cards_from_doc(&entry.doc));
            all_logs.extend(read_review_logs_from_doc(&entry.doc));
        }
        (all_cards, all_logs)
    }

    /// Canonical SHA-256 hash over normalized Loro document state for bit-level convergence verification.
    pub fn compute_page_state_hash(&self, page_id: &str) -> SaltResult<String> {
        let snap = self.get_page_snapshot(page_id)?;
        let logs = {
            let pages = self.pages.read();
            let entry = pages
                .get(page_id)
                .ok_or_else(|| SaltError::PageNotFound(page_id.to_string()))?;
            read_review_logs_from_doc(&entry.doc)
        };
        let mut normalized = CanonicalPageState::from_snapshot(snap, logs);
        normalized.sort_deterministically();
        let bytes = serde_json::to_vec(&normalized).map_err(|e| SaltError::Crdt(e.to_string()))?;
        Ok(sha256_hex(&bytes))
    }

    // ------------------------------------------------------------------------
    // Delta Export, Manifest Maintenance & Remote Delta Import
    // ------------------------------------------------------------------------

    /// Flushes all dirty pages (and Vault root `inbox` doc) into append-only `{ts}_{seq}.delta` files under
    /// `.saltnote/sync/deltas/{my_device_id}/` and updates `{my_device_id}.json` manifest.
    pub fn flush_all_dirty_deltas(&self) -> SaltResult<Vec<DeltaEntry>> {
        let mut dirty_ids: Vec<String> = {
            let pages = self.pages.read();
            pages
                .iter()
                .filter(|(_, e)| e.dirty_since_export)
                .map(|(k, _)| k.clone())
                .collect()
        };
        if self.root_doc.read().dirty_since_export {
            dirty_ids.push(VAULT_ROOT_DOC_ID.to_string());
        }
        let mut created = Vec::new();
        for pid in dirty_ids {
            if let Some(entry) = self.flush_page_delta(&pid)? {
                created.push(entry);
            }
        }
        Ok(created)
    }

    pub fn flush_page_delta(&self, page_id: &str) -> SaltResult<Option<DeltaEntry>> {
        let _flush_guard = self.flush_lock.lock();
        let is_root = page_id == VAULT_ROOT_DOC_ID;
        let (loro_bytes, new_vv) = if is_root {
            let root = self.root_doc.read();
            if !root.dirty_since_export {
                return Ok(None);
            }
            let current_vv = root.doc.oplog_vv();
            let bytes = if root.exported_vv.is_empty() {
                root.doc
                    .export(ExportMode::Snapshot)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?
            } else {
                root.doc
                    .export(ExportMode::updates(&root.exported_vv))
                    .map_err(|e| SaltError::Crdt(e.to_string()))?
            };
            (bytes, current_vv)
        } else {
            let pages = self.pages.read();
            let Some(entry) = pages.get(page_id) else {
                return Err(SaltError::PageNotFound(page_id.to_string()));
            };
            if !entry.dirty_since_export {
                return Ok(None);
            }
            let current_vv = entry.doc.oplog_vv();
            let bytes = if entry.exported_vv.is_empty() {
                entry
                    .doc
                    .export(ExportMode::Snapshot)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?
            } else {
                entry
                    .doc
                    .export(ExportMode::updates(&entry.exported_vv))
                    .map_err(|e| SaltError::Crdt(e.to_string()))?
            };
            (bytes, current_vv)
        };

        let now = now_utc_ms();
        let (device_id, device_name, seq) = {
            let mut st = self.device_state.write();
            st.last_seq += 1;
            (st.device_id.clone(), st.device_name.clone(), st.last_seq)
        };

        let packet = DeltaPacket {
            page_id: page_id.to_string(),
            device_id: device_id.clone(),
            seq,
            created_at_utc: now,
            loro_bytes,
        };
        let payload = serde_json::to_vec(&packet).map_err(|e| SaltError::Crdt(e.to_string()))?;
        let hash = sha256_hex(&payload);
        let filename = format!("{now}_{seq:08}.delta");
        let delta_path = self.paths.device_deltas_dir(&device_id).join(&filename);
        fs::create_dir_all(self.paths.device_deltas_dir(&device_id))
            .map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&delta_path, &payload)?;

        let delta_entry = DeltaEntry {
            seq,
            filename,
            sha256: hash,
            size_bytes: payload.len() as u64,
            page_id: page_id.to_string(),
            created_at_utc: now,
        };

        // Update local manifest
        let mut manifest = self.load_manifest(&device_id)?.unwrap_or(DeviceManifest {
            device_id: device_id.clone(),
            device_name,
            latest_seq: 0,
            updated_at_utc: now,
            deltas: Vec::new(),
        });
        manifest.latest_seq = seq;
        manifest.updated_at_utc = now;
        manifest.deltas.push(delta_entry.clone());
        let manifest_json =
            serde_json::to_string_pretty(&manifest).map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.manifest_path(&device_id), manifest_json.as_bytes())?;

        // Persist updated device_state.json
        let state_json = serde_json::to_string_pretty(&*self.device_state.read())
            .map_err(|e| SaltError::Io(e.to_string()))?;
        atomic_write(&self.paths.device_state_path(), state_json.as_bytes())?;

        if is_root {
            let mut root = self.root_doc.write();
            root.exported_vv = new_vv;
            root.dirty_since_export = false;
        } else if let Some(entry) = self.pages.write().get_mut(page_id) {
            entry.exported_vv = new_vv;
            entry.dirty_since_export = false;
        }

        Ok(Some(delta_entry))
    }

    pub fn load_manifest(&self, device_id: &str) -> SaltResult<Option<DeviceManifest>> {
        let p = self.paths.manifest_path(device_id);
        if !p.exists() {
            return Ok(None);
        }
        let raw = fs::read_to_string(p).map_err(|e| SaltError::Io(e.to_string()))?;
        let m = serde_json::from_str(&raw).map_err(|e| SaltError::Io(e.to_string()))?;
        Ok(Some(m))
    }

    /// Exports full snapshots for all pages & Vault root doc (used for P2P QUIC sync handshake).
    pub fn export_all_page_packets(&self) -> SaltResult<Vec<DeltaPacket>> {
        let dev = self.device_state();
        let pages = self.pages.read();
        let mut packets = Vec::with_capacity(pages.len() + 1);
        for (idx, (page_id, entry)) in pages.iter().enumerate() {
            let loro_bytes = entry
                .doc
                .export(ExportMode::Snapshot)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            packets.push(DeltaPacket {
                page_id: page_id.clone(),
                device_id: dev.device_id.clone(),
                seq: dev.last_seq + idx as u64 + 1,
                created_at_utc: now_utc_ms(),
                loro_bytes,
            });
        }
        let root_bytes = self
            .root_doc
            .read()
            .doc
            .export(ExportMode::Snapshot)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        if !root_bytes.is_empty() {
            packets.push(DeltaPacket {
                page_id: VAULT_ROOT_DOC_ID.to_string(),
                device_id: dev.device_id.clone(),
                seq: dev.last_seq + packets.len() as u64 + 1,
                created_at_utc: now_utc_ms(),
                loro_bytes: root_bytes,
            });
        }
        Ok(packets)
    }

    /// Exports encoded Loro `VersionVector` per page + Vault root doc for P2P V2 diff negotiation.
    pub fn export_page_version_vectors(&self) -> BTreeMap<String, Vec<u8>> {
        let mut out = BTreeMap::new();
        {
            let pages = self.pages.read();
            for (page_id, entry) in pages.iter() {
                out.insert(page_id.clone(), entry.doc.oplog_vv().encode());
            }
        }
        let root_vv = self.root_doc.read().doc.oplog_vv();
        if !root_vv.is_empty() {
            out.insert(VAULT_ROOT_DOC_ID.to_string(), root_vv.encode());
        }
        out
    }

    /// Exports ONLY missing page/root snapshots or incremental Loro updates relative to a peer's version vectors.
    pub fn export_diff_packets_against(
        &self,
        remote_vvs: &BTreeMap<String, Vec<u8>>,
    ) -> SaltResult<Vec<DeltaPacket>> {
        let dev = self.device_state();
        let mut packets = Vec::new();
        let mut idx = 0u64;

        let compute_diff = |doc: &LoroDoc, doc_id: &str| -> SaltResult<Option<Vec<u8>>> {
            let local_vv = doc.oplog_vv();
            if local_vv.is_empty() {
                return Ok(None);
            }
            let loro_bytes = match remote_vvs
                .get(doc_id)
                .and_then(|raw| VersionVector::decode(raw).ok())
            {
                Some(remote_vv) => {
                    let has_missing_ops = local_vv
                        .iter()
                        .any(|(&peer, &local_cnt)| remote_vv.get(&peer).copied().unwrap_or(0) < local_cnt);
                    if !has_missing_ops {
                        return Ok(None);
                    }
                    doc.export(ExportMode::updates(&remote_vv))
                        .map_err(|e| SaltError::Crdt(e.to_string()))?
                }
                None => doc
                    .export(ExportMode::Snapshot)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?,
            };
            if loro_bytes.is_empty() {
                Ok(None)
            } else {
                Ok(Some(loro_bytes))
            }
        };

        {
            let pages = self.pages.read();
            for (page_id, entry) in pages.iter() {
                if let Some(loro_bytes) = compute_diff(&entry.doc, page_id)? {
                    idx += 1;
                    packets.push(DeltaPacket {
                        page_id: page_id.clone(),
                        device_id: dev.device_id.clone(),
                        seq: dev.last_seq + idx,
                        created_at_utc: now_utc_ms(),
                        loro_bytes,
                    });
                }
            }
        }
        {
            let root = self.root_doc.read();
            if let Some(loro_bytes) = compute_diff(&root.doc, VAULT_ROOT_DOC_ID)? {
                idx += 1;
                packets.push(DeltaPacket {
                    page_id: VAULT_ROOT_DOC_ID.to_string(),
                    device_id: dev.device_id.clone(),
                    seq: dev.last_seq + idx,
                    created_at_utc: now_utc_ms(),
                    loro_bytes,
                });
            }
        }
        Ok(packets)
    }

    /// Lists all immutable asset relative paths (`assets/xx/xxxx.webp`) without reading file bytes.
    pub fn list_asset_rel_paths(&self) -> SaltResult<Vec<String>> {
        let assets_root = self.paths.assets_dir();
        if !assets_root.exists() {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        collect_asset_paths(&assets_root, "assets", &mut out)?;
        out.sort();
        Ok(out)
    }

    /// Reads only the requested immutable asset blobs (skipping unsafe or missing paths).
    pub fn read_assets_by_paths(&self, rel_paths: &[String]) -> SaltResult<Vec<(String, Vec<u8>)>> {
        let mut out = Vec::new();
        for rel in rel_paths {
            let Ok(full) = self.asset_abs_path_checked(rel) else {
                continue;
            };
            if full.is_file() {
                if let Ok(bytes) = fs::read(&full) {
                    out.push((rel.clone(), bytes));
                }
            }
        }
        Ok(out)
    }

    /// Exports all immutable WebP assets under `assets/` for complete P2P synchronization.
    pub fn export_all_assets(&self) -> SaltResult<Vec<(String, Vec<u8>)>> {
        let assets_root = self.paths.assets_dir();
        if !assets_root.exists() {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        collect_asset_blobs(&assets_root, "assets", &mut out)?;
        Ok(out)
    }

    /// Imports a remote `.delta` payload, merges into Loro CRDT, persists into local peer delta dir,
    /// refreshes `.md` view, and broadcasts `DocChangeEvent` to `salt-intel`.
    pub fn import_delta_packet_bytes(&self, payload: &[u8]) -> SaltResult<String> {
        let packet: DeltaPacket = serde_json::from_slice(payload)
            .map_err(|e| SaltError::Sync(format!("Corrupt delta packet: {e}")))?;

        if packet.page_id == VAULT_ROOT_DOC_ID {
            {
                let root = self.root_doc.write();
                root.doc
                    .import(&packet.loro_bytes)
                    .map_err(|e| SaltError::Crdt(format!("Loro root import failed: {e}")))?;
            }
            let peer_dir = self.paths.device_deltas_dir(&packet.device_id);
            fs::create_dir_all(&peer_dir).map_err(|e| SaltError::Io(e.to_string()))?;
            let short_hash = &sha256_hex(payload)[..10];
            let fname = format!("{}_{:08}_{}.delta", packet.created_at_utc, packet.seq, short_hash);
            let p = peer_dir.join(fname);
            if !p.exists() {
                atomic_write(&p, payload)?;
            }
            self.bump_inbox_sync_revision();
            self.bump_sync_revision();
            let _ = self.events_tx.send(DocChangeEvent::InboxUpdated);
            return Ok(VAULT_ROOT_DOC_ID.to_string());
        }

        let (meta, body, cards, logs, old_file) = {
            let mut pages = self.pages.write();
            let entry = pages
                .entry(packet.page_id.clone())
                .or_insert_with(|| PageEntry {
                    doc: self.new_loro_doc(),
                    exported_vv: VersionVector::new(),
                    dirty_since_export: false,
                    markdown_file: None,
                });
            entry
                .doc
                .import(&packet.loro_bytes)
                .map_err(|e| SaltError::Crdt(format!("Loro import failed: {e}")))?;
            let meta = read_meta_from_doc(&entry.doc, &packet.page_id)?;
            let body = entry.doc.get_text("body").to_string();
            let cards = read_cards_from_doc(&entry.doc);
            let logs = read_review_logs_from_doc(&entry.doc);
            (meta, body, cards, logs, entry.markdown_file.clone())
        };

        // Record remote delta locally with collision-free hash suffix so cold restarts reproduce full state
        let peer_dir = self.paths.device_deltas_dir(&packet.device_id);
        fs::create_dir_all(&peer_dir).map_err(|e| SaltError::Io(e.to_string()))?;
        let short_hash = &sha256_hex(payload)[..10];
        let fname = format!("{}_{:08}_{}.delta", packet.created_at_utc, packet.seq, short_hash);
        let p = peer_dir.join(fname);
        if !p.exists() {
            atomic_write(&p, payload)?;
        }

        if meta.is_deleted {
            if let Some(prev) = old_file.as_deref() {
                if !prev.is_empty() {
                    let _ = fs::remove_file(self.paths.pages_dir().join(prev));
                }
            }
            let fallback_md = format_markdown_filename(&meta);
            let _ = fs::remove_file(self.paths.pages_dir().join(fallback_md));
            if let Some(entry) = self.pages.write().get_mut(&packet.page_id) {
                entry.markdown_file = None;
            }
            self.bump_sync_revision();
            let _ = self.events_tx.send(DocChangeEvent::PageDeleted {
                page_id: packet.page_id.clone(),
            });
            return Ok(packet.page_id);
        }

        let md_file = self.project_markdown_file(&meta, &body, old_file.as_deref())?;
        if let Some(entry) = self.pages.write().get_mut(&packet.page_id) {
            entry.markdown_file = Some(md_file);
        }

        self.bump_sync_revision();

        let _ = self.events_tx.send(DocChangeEvent::PageUpdated {
            page_id: packet.page_id.clone(),
            meta,
            body_markdown: body,
        });
        let _ = self.events_tx.send(DocChangeEvent::CardsUpdated {
            page_id: packet.page_id.clone(),
            cards,
            review_logs: logs,
        });
        Ok(packet.page_id)
    }

    pub fn update_peer_watermark(&self, peer_device_id: &str, seq: u64) -> SaltResult<()> {
        let json = {
            let mut st = self.device_state.write();
            let cur = st
                .peer_watermarks
                .entry(peer_device_id.to_string())
                .or_insert(0);
            if seq > *cur {
                *cur = seq;
            }
            serde_json::to_string_pretty(&*st).map_err(|e| SaltError::Io(e.to_string()))?
        };
        atomic_write(&self.paths.device_state_path(), json.as_bytes())
    }

    /// Exports all `.md` files and `assets/` into a standalone standard Markdown backup folder.
    pub fn export_markdown_backup(&self, target_dir: impl AsRef<Path>) -> SaltResult<u32> {
        let target = target_dir.as_ref();
        let target_pages = target.join("pages");
        let target_assets = target.join("assets");
        fs::create_dir_all(&target_pages).map_err(|e| SaltError::Io(e.to_string()))?;
        fs::create_dir_all(&target_assets).map_err(|e| SaltError::Io(e.to_string()))?;

        let mut count = 0u32;
        for summary in self.list_pages() {
            let snap = self.get_page_snapshot(&summary.meta.page_id)?;
            let content = render_markdown_with_frontmatter(&snap.meta, &snap.body_markdown);
            let filename = format_markdown_filename(&snap.meta);
            fs::write(target_pages.join(filename), content)
                .map_err(|e| SaltError::Io(e.to_string()))?;
            count += 1;
        }

        if self.paths.assets_dir().exists() {
            copy_dir_recursive(&self.paths.assets_dir(), &target_assets)?;
        }
        Ok(count)
    }

    // ------------------------------------------------------------------------
    // Internal Helpers: WAL, Startup Replay, Markdown Projection
    // ------------------------------------------------------------------------

    fn new_loro_doc(&self) -> LoroDoc {
        let doc = LoroDoc::new();
        let _ = doc.set_peer_id(self.peer_id);
        doc
    }

    fn append_wal(&self, page_id: &str, loro_update: &[u8]) -> SaltResult<()> {
        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.paths.wal_path())
            .map_err(|e| SaltError::Io(format!("WAL open failed: {e}")))?;

        let pid_bytes = page_id.as_bytes();
        let pid_len = pid_bytes.len() as u16;
        let data_len = loro_update.len() as u32;
        file.write_all(&pid_len.to_le_bytes())
            .and_then(|_| file.write_all(pid_bytes))
            .and_then(|_| file.write_all(&data_len.to_le_bytes()))
            .and_then(|_| file.write_all(loro_update))
            .and_then(|_| file.flush())
            .map_err(|e| SaltError::Io(format!("WAL write failed: {e}")))?;
        Ok(())
    }

    fn replay_all_deltas_from_disk(&self) -> SaltResult<()> {
        let deltas_root = self.paths.deltas_dir();
        if !deltas_root.exists() {
            return Ok(());
        }
        let mut delta_files = Vec::new();
        for dev_entry in fs::read_dir(&deltas_root).map_err(|e| SaltError::Io(e.to_string()))? {
            let dev_entry = dev_entry.map_err(|e| SaltError::Io(e.to_string()))?;
            if dev_entry.path().is_dir() {
                for f in
                    fs::read_dir(dev_entry.path()).map_err(|e| SaltError::Io(e.to_string()))?
                {
                    let f = f.map_err(|e| SaltError::Io(e.to_string()))?;
                    let path = f.path();
                    if path.extension().and_then(|s| s.to_str()) == Some("delta") {
                        delta_files.push(path);
                    }
                }
            }
        }
        delta_files.sort();

        let mut pages = self.pages.write();
        let mut root = self.root_doc.write();
        for path in delta_files {
            if let Ok(raw) = fs::read(&path) {
                if let Ok(packet) = serde_json::from_slice::<DeltaPacket>(&raw) {
                    if packet.page_id == VAULT_ROOT_DOC_ID {
                        let _ = root.doc.import(&packet.loro_bytes);
                        root.exported_vv = root.doc.oplog_vv();
                    } else {
                        let entry = pages
                            .entry(packet.page_id.clone())
                            .or_insert_with(|| PageEntry {
                                doc: self.new_loro_doc(),
                                exported_vv: VersionVector::new(),
                                dirty_since_export: false,
                                markdown_file: None,
                            });
                        let _ = entry.doc.import(&packet.loro_bytes);
                        entry.exported_vv = entry.doc.oplog_vv();
                    }
                }
            }
        }
        Ok(())
    }

    fn replay_wal_from_disk(&self) -> SaltResult<()> {
        let wal_path = self.paths.wal_path();
        if !wal_path.exists() {
            return Ok(());
        }
        let bytes = fs::read(&wal_path).map_err(|e| SaltError::Io(e.to_string()))?;
        let mut pos = 0usize;
        let mut pages = self.pages.write();
        let mut root = self.root_doc.write();
        while pos + 2 <= bytes.len() {
            let pid_len = u16::from_le_bytes([bytes[pos], bytes[pos + 1]]) as usize;
            pos += 2;
            if pos + pid_len + 4 > bytes.len() {
                break;
            }
            let Ok(page_id) = std::str::from_utf8(&bytes[pos..pos + pid_len]) else {
                break;
            };
            pos += pid_len;
            let data_len = u32::from_le_bytes([
                bytes[pos],
                bytes[pos + 1],
                bytes[pos + 2],
                bytes[pos + 3],
            ]) as usize;
            pos += 4;
            if pos + data_len > bytes.len() {
                break;
            }
            let chunk = &bytes[pos..pos + data_len];
            pos += data_len;

            if page_id == VAULT_ROOT_DOC_ID {
                let _ = root.doc.import(chunk);
                if root.doc.oplog_vv() != root.exported_vv {
                    root.dirty_since_export = true;
                }
            } else {
                let entry = pages
                    .entry(page_id.to_string())
                    .or_insert_with(|| PageEntry {
                        doc: self.new_loro_doc(),
                        exported_vv: VersionVector::new(),
                        dirty_since_export: true,
                        markdown_file: None,
                    });
                let _ = entry.doc.import(chunk);
                if entry.doc.oplog_vv() != entry.exported_vv {
                    entry.dirty_since_export = true;
                }
            }
        }

        // Refresh projected markdown filenames for recovered docs (skip deleted tombstones)
        for (pid, entry) in pages.iter_mut() {
            if let Ok(meta) = read_meta_from_doc(&entry.doc, pid) {
                if meta.is_deleted {
                    entry.markdown_file = None;
                } else {
                    let fname = format_markdown_filename(&meta);
                    entry.markdown_file = Some(fname);
                }
            }
        }
        Ok(())
    }

    fn project_markdown_file(
        &self,
        meta: &PageMeta,
        body: &str,
        previous_filename: Option<&str>,
    ) -> SaltResult<String> {
        let filename = format_markdown_filename(meta);
        if let Some(prev) = previous_filename {
            if prev != filename && !prev.is_empty() {
                let _ = fs::remove_file(self.paths.pages_dir().join(prev));
            }
        }
        let rendered = render_markdown_with_frontmatter(meta, body);
        atomic_write(&self.paths.pages_dir().join(&filename), rendered.as_bytes())?;
        Ok(filename)
    }
}

// ============================================================================
// Loro Container Serialization Helpers
// ============================================================================

fn write_meta_to_doc(doc: &LoroDoc, meta: &PageMeta) -> SaltResult<()> {
    let map = doc.get_map("meta");
    map.insert("page_id", meta.page_id.as_str())
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("title", meta.title.as_str())
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    let mode_str = match meta.mode {
        PageMode::Journal => "journal",
        PageMode::Study => "study",
    };
    map.insert("mode", mode_str)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("is_private", meta.is_private)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    let tags_json =
        serde_json::to_string(&meta.tags).map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("tags", tags_json)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("created_at_utc", meta.created_at_utc)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("updated_at_utc", meta.updated_at_utc)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    map.insert("is_deleted", meta.is_deleted)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;
    Ok(())
}

fn touch_updated_at(doc: &LoroDoc) -> SaltResult<()> {
    let map = doc.get_map("meta");
    map.insert("updated_at_utc", now_utc_ms())
        .map_err(|e| SaltError::Crdt(e.to_string()))
}

fn read_meta_from_doc(doc: &LoroDoc, fallback_id: &str) -> SaltResult<PageMeta> {
    let val = doc.get_map("meta").get_deep_value();
    let LoroValue::Map(m) = val else {
        return Err(SaltError::Crdt("Invalid meta container".into()));
    };
    let get_str = |k: &str| -> Option<String> {
        match m.get(k) {
            Some(LoroValue::String(s)) => Some(s.to_string()),
            _ => None,
        }
    };
    let get_i64 = |k: &str| -> i64 {
        match m.get(k) {
            Some(LoroValue::I64(n)) => *n,
            _ => 0,
        }
    };
    let get_bool = |k: &str| -> bool {
        match m.get(k) {
            Some(LoroValue::Bool(b)) => *b,
            _ => false,
        }
    };
    let mode = match get_str("mode").as_deref() {
        Some("journal") => PageMode::Journal,
        _ => PageMode::Study,
    };
    let tags = get_str("tags")
        .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok())
        .unwrap_or_default();

    Ok(PageMeta {
        page_id: get_str("page_id").unwrap_or_else(|| fallback_id.to_string()),
        title: get_str("title").unwrap_or_else(|| "未命名笔记".into()),
        mode,
        is_private: get_bool("is_private"),
        tags,
        created_at_utc: get_i64("created_at_utc"),
        updated_at_utc: get_i64("updated_at_utc"),
        is_deleted: get_bool("is_deleted"),
    })
}

fn read_canvases_from_doc(doc: &LoroDoc) -> Vec<CanvasSnapshot> {
    let val = doc.get_map("canvas").get_deep_value();
    let LoroValue::Map(root_map) = val else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for (canvas_id, c_val) in root_map.iter() {
        let LoroValue::Map(c_map) = c_val else {
            continue;
        };
        let mut strokes = Vec::new();
        if let Some(LoroValue::Map(sm)) = c_map.get("strokes") {
            for v in sm.values() {
                if let LoroValue::String(s) = v {
                    if let Ok(st) = serde_json::from_str::<DeltaStroke>(s) {
                        strokes.push(st);
                    }
                }
            }
        }
        let mut transforms = Vec::new();
        if let Some(LoroValue::Map(tm)) = c_map.get("transforms") {
            for v in tm.values() {
                if let LoroValue::String(s) = v {
                    if let Ok(tr) = serde_json::from_str::<StrokeTransform>(s) {
                        transforms.push(tr);
                    }
                }
            }
        }
        let mut images = Vec::new();
        if let Some(LoroValue::Map(im)) = c_map.get("images") {
            for v in im.values() {
                if let LoroValue::String(s) = v {
                    if let Ok(node) = serde_json::from_str::<CanvasImageNode>(s) {
                        images.push(node);
                    }
                }
            }
        }
        strokes.sort_by(|a, b| a.created_at_utc.cmp(&b.created_at_utc).then(a.stroke_id.cmp(&b.stroke_id)));
        transforms.sort_by(|a, b| a.stroke_id.cmp(&b.stroke_id));
        images.sort_by(|a, b| a.image_id.cmp(&b.image_id));
        out.push(CanvasSnapshot {
            canvas_id: canvas_id.clone(),
            strokes,
            transforms,
            images,
        });
    }
    out.sort_by(|a, b| a.canvas_id.cmp(&b.canvas_id));
    out
}

fn read_halos_from_doc(doc: &LoroDoc) -> Vec<HaloAnnotation> {
    let val = doc.get_map("halos").get_deep_value();
    let LoroValue::Map(m) = val else {
        return Vec::new();
    };
    let mut out: Vec<HaloAnnotation> = m
        .values()
        .filter_map(|v| match v {
            LoroValue::String(s) => serde_json::from_str(s).ok(),
            _ => None,
        })
        .collect();
    out.sort_by(|a, b| a.created_at_utc.cmp(&b.created_at_utc).then(a.halo_id.cmp(&b.halo_id)));
    out
}

/// Validates that an asset relative path points strictly under `assets/` with normal path segments.
pub fn validate_safe_asset_rel_path(rel_path: &str) -> SaltResult<()> {
    if rel_path.is_empty()
        || rel_path.contains('\0')
        || rel_path.contains('\\')
        || rel_path.ends_with('/')
        || !rel_path.starts_with("assets/")
    {
        return Err(SaltError::InvalidInput(format!(
            "Unsafe asset path: {rel_path}"
        )));
    }
    let mut normal_count = 0usize;
    for comp in Path::new(rel_path).components() {
        match comp {
            Component::Normal(seg) => {
                if seg.to_string_lossy().is_empty() {
                    return Err(SaltError::InvalidInput(format!(
                        "Empty path segment in asset path: {rel_path}"
                    )));
                }
                normal_count += 1;
            }
            _ => {
                return Err(SaltError::InvalidInput(format!(
                    "Disallowed path component in asset path: {rel_path}"
                )));
            }
        }
    }
    if normal_count < 2 {
        return Err(SaltError::InvalidInput(format!(
            "Incomplete asset path: {rel_path}"
        )));
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct InboxItemBase {
    inbox_id: String,
    kind: InboxKind,
    created_at_utc: i64,
    source_device_id: String,
    payload: InboxPayload,
}

fn parse_transcript_status(raw: &str) -> TranscriptStatus {
    match raw {
        "pending" => TranscriptStatus::Pending,
        "processing" => TranscriptStatus::Processing,
        "waiting_for_lan_relay" => TranscriptStatus::WaitingForLanRelay,
        "completed" => TranscriptStatus::Completed,
        "failed" => TranscriptStatus::Failed,
        _ => TranscriptStatus::NotApplicable,
    }
}

fn ensure_inbox_v2_item_map(doc: &LoroDoc, inbox_id: &str) -> SaltResult<LoroMap> {
    let v2 = doc.get_map("inbox_v2");
    let item_map = v2
        .ensure_mergeable_map(inbox_id)
        .map_err(|e| SaltError::Crdt(e.to_string()))?;

    let has_base = match item_map.get_deep_value() {
        LoroValue::Map(m) => m.contains_key("base"),
        _ => false,
    };
    if !has_base {
        let legacy_item = match doc.get_map("inbox").get_deep_value() {
            LoroValue::Map(m) => m.get(inbox_id).and_then(|v| match v {
                LoroValue::String(s) => serde_json::from_str::<InboxItem>(s).ok(),
                _ => None,
            }),
            _ => None,
        };
        let legacy = legacy_item
            .ok_or_else(|| SaltError::InvalidInput(format!("Inbox item {inbox_id} not found")))?;
        let base = InboxItemBase {
            inbox_id: legacy.inbox_id.clone(),
            kind: legacy.kind,
            created_at_utc: legacy.created_at_utc,
            source_device_id: legacy.source_device_id.clone(),
            payload: legacy.payload.clone(),
        };
        let base_json = serde_json::to_string(&base).map_err(|e| SaltError::Crdt(e.to_string()))?;
        item_map
            .insert("base", base_json)
            .map_err(|e| SaltError::Crdt(e.to_string()))?;
        let existing_keys: std::collections::HashSet<String> = match item_map.get_deep_value() {
            LoroValue::Map(m) => m.keys().cloned().collect(),
            _ => std::collections::HashSet::new(),
        };
        if !existing_keys.contains("is_private") {
            item_map
                .insert("is_private", legacy.is_private)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        if !existing_keys.contains("transcript_status") {
            item_map
                .insert(
                    "transcript_status",
                    legacy.ai_enrichment.transcript_status.as_str(),
                )
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        if !existing_keys.contains("transcript_text") {
            if let Some(txt) = legacy.ai_enrichment.transcript_text.as_deref() {
                item_map
                    .insert("transcript_text", txt)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
        }
        if !existing_keys.contains("summary_text") {
            if let Some(sum) = legacy.ai_enrichment.summary_text.as_deref() {
                item_map
                    .insert("summary_text", sum)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
        }
        if !existing_keys.contains("auto_tags") && !legacy.ai_enrichment.auto_tags.is_empty() {
            let tags_json = serde_json::to_string(&legacy.ai_enrichment.auto_tags)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
            item_map
                .insert("auto_tags", tags_json)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        if !existing_keys.contains("archived") && legacy.state == InboxState::Archived {
            item_map
                .insert("archived", true)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        if !existing_keys.contains("deleted") && legacy.state == InboxState::Deleted {
            item_map
                .insert("deleted", true)
                .map_err(|e| SaltError::Crdt(e.to_string()))?;
        }
        if !existing_keys.contains("consumed_page_id") {
            if let Some(pid) = legacy.consumption.consumed_page_id.as_deref() {
                item_map
                    .insert("consumed_page_id", pid)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
        }
        if !existing_keys.contains("consumed_block_id") {
            if let Some(bid) = legacy.consumption.consumed_block_id.as_deref() {
                item_map
                    .insert("consumed_block_id", bid)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
        }
        if !existing_keys.contains("consumed_at_utc") {
            if let Some(ts) = legacy.consumption.consumed_at_utc {
                item_map
                    .insert("consumed_at_utc", ts)
                    .map_err(|e| SaltError::Crdt(e.to_string()))?;
            }
        }
    }
    Ok(item_map)
}

fn read_inbox_from_doc(doc: &LoroDoc) -> Vec<InboxItem> {
    let mut items_map: HashMap<String, InboxItem> = HashMap::new();

    if let LoroValue::Map(legacy_map) = doc.get_map("inbox").get_deep_value() {
        for (id, v) in legacy_map.iter() {
            if let LoroValue::String(s) = v {
                if let Ok(item) = serde_json::from_str::<InboxItem>(s) {
                    items_map.insert(id.clone(), item);
                }
            }
        }
    }

    if let LoroValue::Map(v2_map) = doc.get_map("inbox_v2").get_deep_value() {
        for (inbox_id, val) in v2_map.iter() {
            let LoroValue::Map(fields) = val else {
                continue;
            };
            let base_opt = fields
                .get("base")
                .and_then(|v| match v {
                    LoroValue::String(s) => serde_json::from_str::<InboxItemBase>(s).ok(),
                    _ => None,
                })
                .map(|b| InboxItem {
                    inbox_id: b.inbox_id,
                    kind: b.kind,
                    created_at_utc: b.created_at_utc,
                    source_device_id: b.source_device_id,
                    is_private: false,
                    state: InboxState::Unprocessed,
                    payload: b.payload,
                    ai_enrichment: InboxAiEnrichment::default(),
                    consumption: InboxConsumption::default(),
                })
                .or_else(|| items_map.get(inbox_id).cloned());

            let Some(mut item) = base_opt else {
                continue;
            };

            if let Some(LoroValue::Bool(p)) = fields.get("is_private") {
                item.is_private = *p;
            }
            if let Some(LoroValue::String(s)) = fields.get("transcript_status") {
                item.ai_enrichment.transcript_status = parse_transcript_status(s);
            }
            if let Some(LoroValue::String(s)) = fields.get("transcript_text") {
                item.ai_enrichment.transcript_text = Some(s.to_string());
            }
            if let Some(LoroValue::String(s)) = fields.get("summary_text") {
                item.ai_enrichment.summary_text = Some(s.to_string());
            }
            if let Some(LoroValue::String(s)) = fields.get("auto_tags") {
                if let Ok(tags) = serde_json::from_str::<Vec<String>>(s) {
                    item.ai_enrichment.auto_tags = tags;
                }
            }
            if let Some(LoroValue::String(s)) = fields.get("consumed_page_id") {
                item.consumption.consumed_page_id = Some(s.to_string());
            }
            if let Some(LoroValue::String(s)) = fields.get("consumed_block_id") {
                item.consumption.consumed_block_id = Some(s.to_string());
            }
            if let Some(LoroValue::I64(ts)) = fields.get("consumed_at_utc") {
                item.consumption.consumed_at_utc = Some(*ts);
            }

            let is_deleted = matches!(fields.get("deleted"), Some(LoroValue::Bool(true)))
                || item.state == InboxState::Deleted;
            let is_archived = matches!(fields.get("archived"), Some(LoroValue::Bool(true)))
                || item.state == InboxState::Archived
                || item.consumption.consumed_page_id.is_some();
            item.state = if is_deleted {
                InboxState::Deleted
            } else if is_archived {
                InboxState::Archived
            } else {
                InboxState::Unprocessed
            };

            items_map.insert(inbox_id.clone(), item);
        }
    }

    items_map.into_values().collect()
}

fn read_cards_from_doc(doc: &LoroDoc) -> Vec<FlashCard> {
    let val = doc.get_map("cards").get_deep_value();
    let LoroValue::Map(m) = val else {
        return Vec::new();
    };
    let mut out: Vec<FlashCard> = m
        .values()
        .filter_map(|v| match v {
            LoroValue::String(s) => serde_json::from_str(s).ok(),
            _ => None,
        })
        .collect();
    out.sort_by(|a, b| a.created_at_utc.cmp(&b.created_at_utc).then(a.card_id.cmp(&b.card_id)));
    out
}

fn read_review_logs_from_doc(doc: &LoroDoc) -> Vec<ReviewLog> {
    let val = doc.get_list("fsrs_logs").get_deep_value();
    let LoroValue::List(list) = val else {
        return Vec::new();
    };
    let mut out: Vec<ReviewLog> = list
        .iter()
        .filter_map(|v| match v {
            LoroValue::String(s) => serde_json::from_str(s).ok(),
            _ => None,
        })
        .collect();
    out.sort_by(|a, b| {
        a.reviewed_at_utc
            .cmp(&b.reviewed_at_utc)
            .then(a.log_id.cmp(&b.log_id))
    });
    out
}

#[derive(Serialize)]
struct CanonicalPageState {
    meta: PageMeta,
    body_markdown: String,
    canvases: Vec<CanvasSnapshot>,
    halos: Vec<HaloAnnotation>,
    cards: Vec<FlashCard>,
    review_logs: Vec<ReviewLog>,
}

impl CanonicalPageState {
    fn from_snapshot(snap: PageSnapshot, review_logs: Vec<ReviewLog>) -> Self {
        Self {
            meta: snap.meta,
            body_markdown: snap.body_markdown,
            canvases: snap.canvases,
            halos: snap.halos,
            cards: snap.cards,
            review_logs,
        }
    }

    fn sort_deterministically(&mut self) {
        self.meta.tags.sort();
        self.canvases.sort_by(|a, b| a.canvas_id.cmp(&b.canvas_id));
        for c in &mut self.canvases {
            c.strokes.sort_by(|a, b| a.stroke_id.cmp(&b.stroke_id));
            c.transforms.sort_by(|a, b| a.stroke_id.cmp(&b.stroke_id));
            c.images.sort_by(|a, b| a.image_id.cmp(&b.image_id));
        }
        self.halos.sort_by(|a, b| a.halo_id.cmp(&b.halo_id));
        self.cards.sort_by(|a, b| a.card_id.cmp(&b.card_id));
        self.review_logs.sort_by(|a, b| {
            a.reviewed_at_utc
                .cmp(&b.reviewed_at_utc)
                .then(a.log_id.cmp(&b.log_id))
        });
    }
}

fn format_markdown_filename(meta: &PageMeta) -> String {
    let dt = Utc
        .timestamp_millis_opt(meta.created_at_utc)
        .single()
        .unwrap_or_else(Utc::now);
    let date_prefix = dt.format("%Y-%m-%d").to_string();
    let safe_title: String = meta
        .title
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect();
    let trimmed = safe_title.trim();
    let base = if trimmed.is_empty() {
        meta.page_id.as_str()
    } else {
        trimmed
    };
    let short_id = &meta.page_id[..meta.page_id.len().min(6)];
    format!("{date_prefix}_{base}_{short_id}.md")
}

fn render_markdown_with_frontmatter(meta: &PageMeta, body: &str) -> String {
    let mode_str = match meta.mode {
        PageMode::Journal => "journal",
        PageMode::Study => "study",
    };
    let tags_yaml = meta
        .tags
        .iter()
        .map(|t| format!("\"{}\"", t.replace('"', "\\\"")))
        .collect::<Vec<_>>()
        .join(", ");
    format!(
        "---\nsalt_page_id: \"{}\"\ntitle: \"{}\"\nmode: {}\nprivate: {}\ntags: [{}]\n---\n\n{}",
        meta.page_id,
        meta.title.replace('"', "\\\""),
        mode_str,
        meta.is_private,
        tags_yaml,
        body
    )
}

fn derive_peer_id(device_id: &str) -> u64 {
    let digest = Sha256::digest(device_id.as_bytes());
    let mut arr = [0u8; 8];
    arr.copy_from_slice(&digest[..8]);
    let v = u64::from_le_bytes(arr);
    if v == 0 { 1 } else { v }
}

fn is_legacy_default_prompt(preset_id: &str, prompt: &str) -> bool {
    let p = prompt.trim();
    match preset_id {
        "builtin.amber_expand" => p == "You are SaltNote's study assistant. Provide a crisp Markdown explanation, intuitive analogy, and key takeaways for the circled note region. Keep it structured and concise.",
        "builtin.glacier_card" => p == "Extract 1 to 3 high-value spaced-repetition flashcards from the circled content. Respond ONLY with a JSON array of objects containing {\"front\": \"...\", \"back\": \"...\"}.",
        "builtin.mineral_ocr" => {
            p == "Transcribe the handwritten notes, formulas, diagrams, or whiteboard image accurately into clean Markdown. Preserve math expressions clearly in code/LaTeX blocks."
                || p == "Transcribe the handwritten notes, formulas, diagrams, or whiteboard image accurately into clean Markdown. Render mathematical expressions using standard LaTeX delimiters ($...$ for inline math and $$...$$ on separate lines for display equations) rather than code blocks."
        }
        _ => false,
    }
}

fn sanitize_router_config(cfg: &RouterConfig) -> RouterConfig {
    let mut sanitized = cfg.clone();
    for p in &mut sanitized.providers {
        let first_line = p
            .base_url
            .lines()
            .map(str::trim)
            .find(|l| !l.is_empty())
            .unwrap_or("");
        p.base_url = first_line
            .split_whitespace()
            .take_while(|tok| !tok.starts_with("sk-"))
            .collect::<Vec<_>>()
            .join("")
            .chars()
            .filter(|c| !c.is_control() && !c.is_whitespace())
            .collect::<String>()
            .trim_end_matches('/')
            .to_string();
        p.model = p
            .model
            .lines()
            .map(str::trim)
            .find(|l| !l.is_empty())
            .unwrap_or("")
            .chars()
            .filter(|c| !c.is_control())
            .collect::<String>()
            .trim()
            .to_string();
        p.api_key_ref = p.api_key_ref.trim().to_string();
    }
    sanitized
}

fn atomic_write(path: &Path, bytes: &[u8]) -> SaltResult<()> {
    let unique_tmp = path.with_file_name(format!(
        ".{}.{}.tmp",
        path.file_name().and_then(|s| s.to_str()).unwrap_or("file"),
        new_ulid()
    ));
    fs::write(&unique_tmp, bytes).map_err(|e| SaltError::Io(e.to_string()))?;
    if fs::rename(&unique_tmp, path).is_err() {
        // Windows fallback when destination is briefly scanned or already exists
        let _ = fs::remove_file(&unique_tmp);
        fs::write(path, bytes).map_err(|e| SaltError::Io(e.to_string()))?;
    }
    Ok(())
}

fn collect_asset_paths(dir: &Path, prefix: &str, out: &mut Vec<String>) -> SaltResult<()> {
    for entry in fs::read_dir(dir).map_err(|e| SaltError::Io(e.to_string()))? {
        let entry = entry.map_err(|e| SaltError::Io(e.to_string()))?;
        let name = entry.file_name().to_string_lossy().to_string();
        if name.ends_with(".tmp") {
            continue;
        }
        let rel = format!("{prefix}/{name}");
        let ty = entry.file_type().map_err(|e| SaltError::Io(e.to_string()))?;
        if ty.is_dir() {
            collect_asset_paths(&entry.path(), &rel, out)?;
        } else {
            out.push(rel);
        }
    }
    Ok(())
}

fn collect_asset_blobs(dir: &Path, prefix: &str, out: &mut Vec<(String, Vec<u8>)>) -> SaltResult<()> {
    for entry in fs::read_dir(dir).map_err(|e| SaltError::Io(e.to_string()))? {
        let entry = entry.map_err(|e| SaltError::Io(e.to_string()))?;
        let name = entry.file_name().to_string_lossy().to_string();
        if name.ends_with(".tmp") {
            continue;
        }
        let rel = format!("{prefix}/{name}");
        let ty = entry.file_type().map_err(|e| SaltError::Io(e.to_string()))?;
        if ty.is_dir() {
            collect_asset_blobs(&entry.path(), &rel, out)?;
        } else if let Ok(bytes) = fs::read(entry.path()) {
            out.push((rel, bytes));
        }
    }
    Ok(())
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> SaltResult<()> {
    fs::create_dir_all(dst).map_err(|e| SaltError::Io(e.to_string()))?;
    for entry in fs::read_dir(src).map_err(|e| SaltError::Io(e.to_string()))? {
        let entry = entry.map_err(|e| SaltError::Io(e.to_string()))?;
        let ty = entry.file_type().map_err(|e| SaltError::Io(e.to_string()))?;
        let dst_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dst_path)?;
        } else {
            fs::copy(entry.path(), dst_path).map_err(|e| SaltError::Io(e.to_string()))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::{BrushKind, RectF};

    fn make_dummy_stroke(id: &str, x: f32) -> DeltaStroke {
        DeltaStroke {
            stroke_id: id.into(),
            brush: BrushKind::Pen,
            color_argb: 0xFF112233,
            base_width: 2.5,
            origin_x: x,
            origin_y: 50.0,
            quant_scale: 4.0,
            dx: vec![0, 4, 4],
            dy: vec![0, 2, 2],
            pressure_q: vec![128, 160, 140],
            dt_ms: vec![0, 4, 4],
            bbox: RectF {
                min_x: x,
                min_y: 48.0,
                max_x: x + 4.0,
                max_y: 54.0,
            },
            created_at_utc: now_utc_ms(),
        }
    }

    #[test]
    fn wal_crash_recovery_preserves_text_and_strokes() {
        let tmp = std::env::temp_dir().join(format!("saltnote_wal_test_{}", new_ulid()));
        let page_id = {
            let store = VaultStore::open(&tmp, Some("DeviceA".into())).unwrap();
            let meta = store
                .create_page("Crash Test", PageMode::Study, false, vec![], "Hello WAL")
                .unwrap();
            store
                .add_stroke(&meta.page_id, "main", &make_dummy_stroke("s1", 10.0))
                .unwrap();
            // Intentionally drop without calling flush_all_dirty_deltas (simulates sudden kill)
            meta.page_id
        };

        let reopened = VaultStore::open(&tmp, Some("DeviceA".into())).unwrap();
        let snap = reopened.get_page_snapshot(&page_id).unwrap();
        assert_eq!(snap.body_markdown, "Hello WAL");
        assert_eq!(snap.canvases[0].strokes.len(), 1);
        assert_eq!(snap.canvases[0].strokes[0].stroke_id, "s1");

        let _ = fs::remove_dir_all(&tmp);
    }

    /// MVP Section 6.2 Test 1: 3-device offline concurrent CRDT edit convergence test.
    #[test]
    fn three_device_offline_concurrent_edits_converge_bit_identically() {
        let base = std::env::temp_dir().join(format!("saltnote_3dev_{}", new_ulid()));
        let d1 = VaultStore::open(base.join("d1"), Some("Tablet".into())).unwrap();
        let d2 = VaultStore::open(base.join("d2"), Some("Laptop".into())).unwrap();
        let d3 = VaultStore::open(base.join("d3"), Some("Desktop".into())).unwrap();

        // Seed common base document from d1 -> d2, d3
        let meta = d1
            .create_page(
                "Physics Lecture",
                PageMode::Study,
                false,
                vec!["physics".into()],
                "# Physics\n\nParagraph A\n\nParagraph B\n\nParagraph C\n",
            )
            .unwrap();
        d1.add_stroke(&meta.page_id, "main", &make_dummy_stroke("shared-stroke", 5.0))
            .unwrap();
        let seed_entries = d1.flush_all_dirty_deltas().unwrap();
        let seed_bytes = fs::read(
            d1.paths()
                .device_deltas_dir(&d1.device_state().device_id)
                .join(&seed_entries[0].filename),
        )
        .unwrap();
        d2.import_delta_packet_bytes(&seed_bytes).unwrap();
        d3.import_delta_packet_bytes(&seed_bytes).unwrap();

        // All 3 devices go offline and concurrently edit Markdown, add strokes, and drag shared-stroke
        d1.update_page_body(
            &meta.page_id,
            "# Physics\n\nParagraph A (edited by Tablet)\n\nParagraph B\n\nParagraph C\n",
        )
        .unwrap();
        d1.add_stroke(&meta.page_id, "main", &make_dummy_stroke("stroke-d1", 100.0))
            .unwrap();
        d1.upsert_stroke_transform(
            &meta.page_id,
            "main",
            &StrokeTransform {
                stroke_id: "shared-stroke".into(),
                tx: 12.0,
                ty: 5.0,
                scale_x: 1.0,
                scale_y: 1.0,
                deleted: false,
                updated_at_utc: 1001,
            },
        )
        .unwrap();

        d2.update_page_body(
            &meta.page_id,
            "# Physics\n\nParagraph A\n\nParagraph B (edited by Laptop)\n\nParagraph C\n",
        )
        .unwrap();
        d2.add_stroke(&meta.page_id, "main", &make_dummy_stroke("stroke-d2", 200.0))
            .unwrap();
        d2.upsert_stroke_transform(
            &meta.page_id,
            "main",
            &StrokeTransform {
                stroke_id: "shared-stroke".into(),
                tx: 30.0,
                ty: -10.0,
                scale_x: 1.0,
                scale_y: 1.0,
                deleted: false,
                updated_at_utc: 1002,
            },
        )
        .unwrap();

        d3.update_page_body(
            &meta.page_id,
            "# Physics\n\nParagraph A\n\nParagraph B\n\nParagraph C (edited by Desktop)\n",
        )
        .unwrap();
        d3.add_stroke(&meta.page_id, "main", &make_dummy_stroke("stroke-d3", 300.0))
            .unwrap();

        // Collect all deltas and deliver in DIFFERENT permutations to d1, d2, d3
        let read_all = |s: &VaultStore| -> Vec<Vec<u8>> {
            let entries = s.flush_all_dirty_deltas().unwrap();
            let dir = s.paths().device_deltas_dir(&s.device_state().device_id);
            entries
                .into_iter()
                .map(|e| fs::read(dir.join(e.filename)).unwrap())
                .collect()
        };
        let p1 = read_all(&d1);
        let p2 = read_all(&d2);
        let p3 = read_all(&d3);

        // d1 receives p2 then p3
        for b in p2.iter().chain(p3.iter()) {
            d1.import_delta_packet_bytes(b).unwrap();
        }
        // d2 receives p3 then p1
        for b in p3.iter().chain(p1.iter()) {
            d2.import_delta_packet_bytes(b).unwrap();
        }
        // d3 receives p1 then p2
        for b in p1.iter().chain(p2.iter()) {
            d3.import_delta_packet_bytes(b).unwrap();
        }

        let h1 = d1.compute_page_state_hash(&meta.page_id).unwrap();
        let h2 = d2.compute_page_state_hash(&meta.page_id).unwrap();
        let h3 = d3.compute_page_state_hash(&meta.page_id).unwrap();
        assert_eq!(h1, h2, "d1 and d2 state hashes must be bit-identical");
        assert_eq!(h2, h3, "d2 and d3 state hashes must be bit-identical");

        let snap = d1.get_page_snapshot(&meta.page_id).unwrap();
        assert_eq!(snap.canvases[0].strokes.len(), 4);
        assert!(snap.body_markdown.contains("Tablet"));
        assert!(snap.body_markdown.contains("Laptop"));
        assert!(snap.body_markdown.contains("Desktop"));

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn page_tombstone_deletion_syncs_and_does_not_resurrect() {
        let base = std::env::temp_dir().join(format!("saltnote_del_{}", new_ulid()));
        let d1 = VaultStore::open(base.join("d1"), Some("Tablet".into())).unwrap();
        let d2 = VaultStore::open(base.join("d2"), Some("Desktop".into())).unwrap();

        let meta = d1
            .create_page("Temp Note", PageMode::Study, false, vec!["tag1".into()], "Secret draft")
            .unwrap();
        let v2_vvs = d2.export_page_version_vectors();
        for pkt in d1.export_diff_packets_against(&v2_vvs).unwrap() {
            d2.import_delta_packet_bytes(&serde_json::to_vec(&pkt).unwrap())
                .unwrap();
        }
        assert_eq!(d2.list_pages().len(), 1);

        // Delete on d1, propagate diff to d2
        d1.delete_page(&meta.page_id).unwrap();
        assert!(d1.list_pages().is_empty());

        let v2_vvs_after = d2.export_page_version_vectors();
        let diff = d1.export_diff_packets_against(&v2_vvs_after).unwrap();
        assert_eq!(diff.len(), 1);
        for pkt in diff {
            d2.import_delta_packet_bytes(&serde_json::to_vec(&pkt).unwrap())
                .unwrap();
        }
        assert!(d2.list_pages().is_empty());
        // Projected markdown file must also be removed on peer
        let md_files: Vec<_> = fs::read_dir(d2.paths().pages_dir())
            .unwrap()
            .flatten()
            .collect();
        assert!(md_files.is_empty());

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn fuse_halo_formats_semantic_callouts_and_glacier_cards_without_raw_json() {
        let base = std::env::temp_dir().join(format!("saltnote_fuse_{}", new_ulid()));
        let store = VaultStore::open(&base, Some("Dev".into())).unwrap();
        let meta = store
            .create_page("Calculus", PageMode::Study, false, vec![], "# Calculus")
            .unwrap();

        let glacier = HaloAnnotation {
            halo_id: "h-glacier".into(),
            page_id: meta.page_id.clone(),
            canvas_id: Some("main".into()),
            preset_id: "builtin.glacier_card".into(),
            anchor_bbox: None,
            anchor_text_offset: None,
            pin_x: None,
            pin_y: None,
            slice_cache_path: None,
            slice_asset_rel_path: None,
            context_excerpt: String::new(),
            response_markdown: "```json\n[{\"front\":\"$\\\\frac{d}{dx} e^x = ?$\",\"back\":\"$e^x$\"}]\n```".into(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        store.upsert_halo(&glacier).unwrap();
        let body = store.fuse_halo_into_body(&meta.page_id, "h-glacier").unwrap();
        assert!(body.contains("> [!salt-glacier] 冰川蓝 · 提炼闪卡"));
        assert!(body.contains("> **Q1**："));
        assert!(body.contains("> **A1**："));
        assert!(!body.contains("\"front\""));

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn card_update_and_delete_preserve_logs_and_tombstone() {
        let base = std::env::temp_dir().join(format!("saltnote_cards_{}", new_ulid()));
        let store = VaultStore::open(&base, Some("Dev".into())).unwrap();
        let meta = store
            .create_page("Cards", PageMode::Study, false, vec![], "Body")
            .unwrap();
        let card = FlashCard {
            card_id: "c1".into(),
            page_id: meta.page_id.clone(),
            anchor_halo_id: None,
            front: "Old Q".into(),
            back: "Old A".into(),
            created_at_utc: now_utc_ms(),
            archived: false,
        };
        store.upsert_card(&card).unwrap();
        let updated = store
            .update_card(&meta.page_id, "c1", "New Q", "New A")
            .unwrap();
        assert_eq!(updated.front, "New Q");
        assert_eq!(updated.back, "New A");
        assert!(!updated.archived);

        store.delete_card(&meta.page_id, "c1").unwrap();
        let snap = store.get_page_snapshot(&meta.page_id).unwrap();
        assert!(snap.cards[0].archived);

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn halo_pin_move_image_transform_and_ocr_prompt_migration_work() {
        let base = std::env::temp_dir().join(format!("saltnote_pin_img_{}", new_ulid()));
        // Seed legacy v0.1-alpha halo_presets.json before opening
        let cfg_dir = base.join(".saltnote").join("config");
        fs::create_dir_all(&cfg_dir).unwrap();
        let legacy_presets = r##"[
          {"preset_id":"builtin.amber_expand","name":"Amber","color_hex":"#F5C242","color_argb":4294296130,"vision_policy":"text_short_circuit_if_clean","output_mode":"markdown_expand","system_prompt":"You are SaltNote's study assistant. Provide a crisp Markdown explanation, intuitive analogy, and key takeaways for the circled note region. Keep it structured and concise."},
          {"preset_id":"builtin.mineral_ocr","name":"OCR","color_hex":"#8D99AE","color_argb":4287470000,"vision_policy":"force_webp_slice","output_mode":"ocr_markdown","system_prompt":"Transcribe the handwritten notes, formulas, diagrams, or whiteboard image accurately into clean Markdown. Preserve math expressions clearly in code/LaTeX blocks."}
        ]"##;
        fs::write(cfg_dir.join("halo_presets.json"), legacy_presets).unwrap();

        let store = VaultStore::open(&base, Some("Dev".into())).unwrap();
        let migrated = store.load_halo_presets().unwrap();
        let ocr = migrated
            .iter()
            .find(|p| p.preset_id == "builtin.mineral_ocr")
            .unwrap();
        assert!(ocr.system_prompt.contains("verbatim OCR transcriber"));

        let meta = store
            .create_page("Canvas Page", PageMode::Journal, false, vec![], "")
            .unwrap();
        let halo = HaloAnnotation {
            halo_id: "h1".into(),
            page_id: meta.page_id.clone(),
            canvas_id: Some("main".into()),
            preset_id: "builtin.amber_expand".into(),
            anchor_bbox: Some(RectF {
                min_x: 10.0,
                min_y: 20.0,
                max_x: 110.0,
                max_y: 90.0,
            }),
            anchor_text_offset: None,
            pin_x: Some(110.0),
            pin_y: Some(20.0),
            slice_cache_path: None,
            slice_asset_rel_path: None,
            context_excerpt: String::new(),
            response_markdown: "Done".into(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        store.upsert_halo(&halo).unwrap();
        let moved = store.update_halo_pin(&meta.page_id, "h1", 240.5, 165.0).unwrap();
        assert_eq!(moved.pin_x, Some(240.5));
        assert_eq!(moved.pin_y, Some(165.0));
        store.delete_halo(&meta.page_id, "h1").unwrap();
        let snap_after_del = store.get_page_snapshot(&meta.page_id).unwrap();
        assert!(snap_after_del.halos.is_empty());

        let img = CanvasImageNode {
            image_id: "img1".into(),
            asset_hash: "abc".into(),
            asset_rel_path: "assets/ab/abc.webp".into(),
            x: 50.0,
            y: 60.0,
            width: 300.0,
            height: 200.0,
            deleted: false,
            updated_at_utc: now_utc_ms(),
        };
        store.upsert_canvas_image(&meta.page_id, "main", &img).unwrap();
        let tr = store
            .update_canvas_image_transform(&meta.page_id, "main", "img1", 120.0, 140.0, 450.0, 300.0, false)
            .unwrap();
        assert_eq!(tr.x, 120.0);
        assert_eq!(tr.width, 450.0);

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn inbox_item_crdt_three_device_convergence_and_consume_into_page() {
        let base = std::env::temp_dir().join(format!("saltnote_inbox_3dev_{}", new_ulid()));
        let phone = VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap();
        let tablet = VaultStore::open(base.join("tablet"), Some("Tablet".into())).unwrap();
        let pc = VaultStore::open(base.join("pc"), Some("Desktop".into())).unwrap();

        let photo = phone
            .capture_inbox_item(
                InboxKind::PhotoNote,
                Some("黑板上的格林公式例题".into()),
                Some(b"FAKE_WEBP_BYTES"),
                Some("webp"),
                Some(b"FAKE_THUMB_WEBP"),
                0,
                false,
            )
            .unwrap();
        let voice = phone
            .capture_inbox_item(
                InboxKind::VoiceMemo,
                None,
                Some(b"FAKE_OPUS_BYTES"),
                Some("ogg"),
                None,
                14_500,
                true,
            )
            .unwrap();
        assert_eq!(
            voice.ai_enrichment.transcript_status,
            TranscriptStatus::WaitingForLanRelay
        );
        // Vault-level root doc must NOT pollute `list_pages()`
        assert!(phone.list_pages().is_empty());

        let page = tablet
            .create_page("高数笔记", PageMode::Study, false, vec![], "# 格林公式")
            .unwrap();

        let p_deltas = phone.flush_all_dirty_deltas().unwrap();
        let p_dir = phone.paths().device_deltas_dir(&phone.device_state().device_id);
        let p_bytes: Vec<Vec<u8>> = p_deltas
            .iter()
            .map(|e| fs::read(p_dir.join(&e.filename)).unwrap())
            .collect();

        let t_deltas = tablet.flush_all_dirty_deltas().unwrap();
        let t_dir = tablet.paths().device_deltas_dir(&tablet.device_state().device_id);
        let t_bytes: Vec<Vec<u8>> = t_deltas
            .iter()
            .map(|e| fs::read(t_dir.join(&e.filename)).unwrap())
            .collect();

        for b in p_bytes.iter().chain(t_bytes.iter()) {
            pc.import_delta_packet_bytes(b).unwrap();
        }
        for b in p_bytes.iter() {
            tablet.import_delta_packet_bytes(b).unwrap();
        }
        for b in t_bytes.iter() {
            phone.import_delta_packet_bytes(b).unwrap();
        }

        // Tablet consumes photo into page while PC enriches voice memo concurrently
        tablet
            .consume_inbox_into_page(&photo.inbox_id, &page.page_id, "blk_1")
            .unwrap();
        pc.update_inbox_enrichment(
            &voice.inbox_id,
            TranscriptStatus::Completed,
            Some("格林公式把闭合曲线积分转化为二重积分".into()),
            Some("曲线积分与二重积分联系".into()),
            Some(vec!["高数".into()]),
        )
        .unwrap();

        let t_after = tablet.flush_all_dirty_deltas().unwrap();
        let pc_after = pc.flush_all_dirty_deltas().unwrap();
        let t_after_bytes: Vec<Vec<u8>> = t_after
            .iter()
            .map(|e| fs::read(t_dir.join(&e.filename)).unwrap())
            .collect();
        let pc_dir = pc.paths().device_deltas_dir(&pc.device_state().device_id);
        let pc_after_bytes: Vec<Vec<u8>> = pc_after
            .iter()
            .map(|e| fs::read(pc_dir.join(&e.filename)).unwrap())
            .collect();

        for b in t_after_bytes.iter().chain(pc_after_bytes.iter()) {
            phone.import_delta_packet_bytes(b).unwrap();
        }
        for b in pc_after_bytes.iter() {
            tablet.import_delta_packet_bytes(b).unwrap();
        }
        for b in t_after_bytes.iter() {
            pc.import_delta_packet_bytes(b).unwrap();
        }

        assert_eq!(
            phone.compute_inbox_state_hash().unwrap(),
            tablet.compute_inbox_state_hash().unwrap()
        );
        assert_eq!(
            tablet.compute_inbox_state_hash().unwrap(),
            pc.compute_inbox_state_hash().unwrap()
        );
        assert_eq!(phone.list_inbox_items(false, 50).len(), 1);
        let all = phone.list_inbox_items(true, 50);
        assert_eq!(all.len(), 2);
        let consumed = all.iter().find(|i| i.inbox_id == photo.inbox_id).unwrap();
        assert_eq!(consumed.state, InboxState::Archived);
        assert_eq!(
            consumed.consumption.consumed_page_id.as_deref(),
            Some(page.page_id.as_str())
        );

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn inbox_v2_concurrent_enrichment_and_consume_merge_without_clobber() {
        let base = std::env::temp_dir().join(format!("saltnote_inbox_v2_merge_{}", new_ulid()));
        let phone = VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap();
        let tablet = VaultStore::open(base.join("tablet"), Some("Tablet".into())).unwrap();

        let voice = phone
            .capture_inbox_item(
                InboxKind::VoiceMemo,
                None,
                Some(b"OPUS_VOICE_DATA"),
                Some("ogg"),
                None,
                8_500,
                true,
            )
            .unwrap();
        let page = tablet
            .create_page("并发测试页", PageMode::Study, false, vec![], "# Body")
            .unwrap();

        // Sync initial creation from phone -> tablet
        let p_init = phone.flush_all_dirty_deltas().unwrap();
        let p_dir = phone.paths().device_deltas_dir(&phone.device_state().device_id);
        for e in &p_init {
            tablet
                .import_delta_packet_bytes(&fs::read(p_dir.join(&e.filename)).unwrap())
                .unwrap();
        }

        // Concurrent mutations on offline replicas:
        // Phone completes SaltRelay enrichment; Tablet concurrently consumes item into a page.
        phone
            .update_inbox_enrichment(
                &voice.inbox_id,
                TranscriptStatus::Completed,
                Some("拉格朗日乘数法转写内容".into()),
                Some("最优化方法要点".into()),
                Some(vec!["高数".into(), "微积分".into()]),
            )
            .unwrap();
        tablet
            .consume_inbox_into_page(&voice.inbox_id, &page.page_id, "blk_99")
            .unwrap();

        let p_delta = phone.flush_all_dirty_deltas().unwrap();
        let t_delta = tablet.flush_all_dirty_deltas().unwrap();
        let t_dir = tablet
            .paths()
            .device_deltas_dir(&tablet.device_state().device_id);

        for e in &p_delta {
            tablet
                .import_delta_packet_bytes(&fs::read(p_dir.join(&e.filename)).unwrap())
                .unwrap();
        }
        for e in &t_delta {
            phone
                .import_delta_packet_bytes(&fs::read(t_dir.join(&e.filename)).unwrap())
                .unwrap();
        }

        assert_eq!(
            phone.compute_inbox_state_hash().unwrap(),
            tablet.compute_inbox_state_hash().unwrap()
        );
        let merged = phone.get_inbox_item(&voice.inbox_id).unwrap();
        assert_eq!(merged.state, InboxState::Archived);
        assert_eq!(
            merged.consumption.consumed_page_id.as_deref(),
            Some(page.page_id.as_str())
        );
        assert_eq!(
            merged.ai_enrichment.transcript_status,
            TranscriptStatus::Completed
        );
        assert_eq!(
            merged.ai_enrichment.transcript_text.as_deref(),
            Some("拉格朗日乘数法转写内容")
        );
        assert_eq!(
            merged.ai_enrichment.summary_text.as_deref(),
            Some("最优化方法要点")
        );
        assert_eq!(merged.ai_enrichment.auto_tags, vec!["高数", "微积分"]);

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn inbox_v2_reads_and_migrates_legacy_v02_alpha_string_items() {
        let base = std::env::temp_dir().join(format!("saltnote_inbox_legacy_{}", new_ulid()));
        let store = VaultStore::open(&base, Some("LegacyNode".into())).unwrap();
        let page = store
            .create_page("Target", PageMode::Study, false, vec![], "")
            .unwrap();

        let legacy_item = InboxItem {
            inbox_id: "inbx_legacy_001".into(),
            kind: InboxKind::QuickText,
            created_at_utc: 1_760_000_000_000,
            source_device_id: "old_phone".into(),
            is_private: false,
            state: InboxState::Unprocessed,
            payload: InboxPayload {
                text_note: Some("旧版字符串盐池条目".into()),
                ..Default::default()
            },
            ai_enrichment: InboxAiEnrichment {
                transcript_status: TranscriptStatus::Pending,
                ..Default::default()
            },
            consumption: InboxConsumption::default(),
        };
        {
            let root = store.root_doc.write();
            let map = root.doc.get_map("inbox");
            map.insert(
                &legacy_item.inbox_id,
                serde_json::to_string(&legacy_item).unwrap(),
            )
            .unwrap();
            root.doc.commit();
        }

        assert_eq!(store.list_inbox_items(false, 20).len(), 1);
        store
            .update_inbox_enrichment(
                "inbx_legacy_001",
                TranscriptStatus::Completed,
                Some("迁移后保留正文并补充摘要".into()),
                Some("摘要".into()),
                Some(vec!["legacy".into()]),
            )
            .unwrap();
        store
            .consume_inbox_into_page("inbx_legacy_001", &page.page_id, "blk_old")
            .unwrap();

        let migrated = store.get_inbox_item("inbx_legacy_001").unwrap();
        assert_eq!(migrated.state, InboxState::Archived);
        assert_eq!(
            migrated.payload.text_note.as_deref(),
            Some("旧版字符串盐池条目")
        );
        assert_eq!(
            migrated.ai_enrichment.transcript_text.as_deref(),
            Some("迁移后保留正文并补充摘要")
        );

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn store_asset_bytes_rejects_path_traversal_and_non_assets_prefix() {
        let base = std::env::temp_dir().join(format!("saltnote_asset_sec_{}", new_ulid()));
        let store = VaultStore::open(&base, Some("SecNode".into())).unwrap();

        for bad in [
            "../escape.txt",
            "/etc/passwd",
            "assets/../evil.webp",
            "assets\\aa\\bb.webp",
            "pages/foo.md",
            "assets/",
            "assets",
            "assets/\0bad.webp",
        ] {
            assert!(
                store.store_asset_bytes(bad, b"x").is_err(),
                "Expected path '{bad}' to be rejected"
            );
        }
        assert!(store
            .store_asset_bytes("assets/ab/abcdef0123456789.webp", b"ok")
            .is_ok());

        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn paired_peers_and_sync_prefs_roundtrip_under_local_dir() {
        let base = std::env::temp_dir().join(format!("saltnote_trust_store_{}", new_ulid()));
        let store = VaultStore::open(&base, Some("TrustNode".into())).unwrap();

        assert!(store.load_paired_peers().is_empty());
        assert!(!store.is_peer_paired("ABCDEF1234567890"));

        let peer = PairedPeer {
            device_id: "dev_tablet_1".into(),
            device_name: "Galaxy Tab".into(),
            cert_fingerprint: "ABCDEF1234567890ABCDEF1234567890".into(),
            last_host: "192.168.1.44".into(),
            last_port: 47821,
            paired_at_utc: 1000,
            last_sync_utc: 0,
            auto_sync: true,
        };
        store.upsert_paired_peer(peer).unwrap();
        assert!(store.is_peer_paired("abcdef1234567890abcdef1234567890"));
        assert!(store.paths().paired_peers_path().exists());

        store
            .update_paired_peer_endpoint("dev_tablet_1", "192.168.1.88", 47821, None)
            .unwrap();
        store
            .touch_paired_peer_sync("abcdef1234567890abcdef1234567890", 2000)
            .unwrap();
        let loaded = store.load_paired_peers();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].last_host, "192.168.1.88");
        assert_eq!(loaded[0].last_sync_utc, 2000);

        let mut prefs = store.load_sync_prefs();
        assert!(prefs.lan_auto_start && prefs.auto_sync_on_capture);
        prefs.mirror_webdav_after_lan = true;
        store.save_sync_prefs(&prefs).unwrap();
        assert!(store.load_sync_prefs().mirror_webdav_after_lan);

        store.remove_paired_peer("dev_tablet_1").unwrap();
        assert!(!store.is_peer_paired("abcdef1234567890abcdef1234567890"));

        let _ = fs::remove_dir_all(&base);
    }
}
