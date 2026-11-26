use once_cell::sync::Lazy;
use parking_lot::{Mutex, RwLock};
use salt_canvas::{
    build_render_stroke, extract_markdown_context, ingest_raw_stroke_bytes,
    prepare_image_asset_webp, render_canvas_slice_webp, tessellate_stroke, CanvasSpatialIndex,
};
use salt_crdt::VaultStore;
use salt_intel::IntelEngine;
use salt_router::{
    parse_flashcard_drafts, HaloInferenceRequest, InboxEnrichmentRequest, SaltRouter,
    SecretResolver, StreamSink,
};
use salt_sync::{
    BackoffSchedule, LanP2pNode, RelayEnrichRequest, RemoteSyncScope, SaltRelayHandler,
    WebDavSyncClient,
};
use salt_types::{
    content_addressed_asset_rel_path, decode_raw_points_le, is_local_or_rfc1918_endpoint,
    new_ulid, now_utc_ms, redact_sensitive_text, AiProviderKind, BrushKind, CanvasImageNode,
    CanvasSnapshot, FlashCard, HaloAnnotation, InboxItem, LocalSyncPrefs, PageMode, ProviderConfig,
    RectF, ReviewLog, ReviewRating, RouterConfig, SaltError, SaltResult, StrokeTransform,
    SyncNetworkPolicy, TranscriptStatus, VisionPolicy, WebDavConfig,
};
use serde::Serialize;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use thiserror::Error;
use tokio::runtime::{Builder as RtBuilder, Runtime};
use tokio_util::sync::CancellationToken;

uniffi::setup_scaffolding!();

static GLOBAL_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    let _ = rustls::crypto::ring::default_provider().install_default();
    RtBuilder::new_multi_thread()
        .worker_threads(4)
        .thread_name("saltnote-rt")
        .enable_all()
        .build()
        .expect("Failed to initialize SaltNote Tokio runtime")
});

#[derive(Debug, Error, uniffi::Error)]
pub enum FfiError {
    #[error("PrivacyBlocked: {detail}")]
    PrivacyBlocked { detail: String },
    #[error("NotFound: {detail}")]
    NotFound { detail: String },
    #[error("InvalidInput: {detail}")]
    InvalidInput { detail: String },
    #[error("Cancelled")]
    Cancelled,
    #[error("Internal: {detail}")]
    Internal { detail: String },
}

impl From<SaltError> for FfiError {
    fn from(value: SaltError) -> Self {
        match value {
            SaltError::PrivacyBlocked { endpoint, reason } => FfiError::PrivacyBlocked {
                detail: format!("{endpoint}: {reason}"),
            },
            SaltError::PageNotFound(m) | SaltError::SecretNotFound(m) => {
                FfiError::NotFound { detail: m }
            }
            SaltError::InvalidInput(m) => FfiError::InvalidInput { detail: m },
            SaltError::Cancelled => FfiError::Cancelled,
            other => FfiError::Internal {
                detail: redact_sensitive_text(&other.to_string()),
            },
        }
    }
}

/// Platform-backed OS secure secret storage implemented in Kotlin (Android Keystore / Windows DPAPI / Keychain / SecretService).
#[uniffi::export(callback_interface)]
pub trait PlatformSecretStore: Send + Sync {
    fn get_secret(&self, key_ref: String) -> Result<String, FfiError>;
}

/// Streaming callback interface for Salt Halo crystal bubbles.
#[uniffi::export(callback_interface)]
pub trait HaloStreamCallback: Send + Sync {
    fn on_delta(&self, chunk: String);
    fn on_complete(&self, full_markdown: String, halo_json: String);
    fn on_error(&self, code: String, message: String);
}

struct FfiSecretBridge {
    inner: Box<dyn PlatformSecretStore>,
}

impl SecretResolver for FfiSecretBridge {
    fn resolve_secret(&self, key_ref: &str) -> SaltResult<String> {
        self.inner
            .get_secret(key_ref.to_string())
            .map_err(|e| SaltError::SecretNotFound(e.to_string()))
    }
}

struct FfiStreamBridge {
    callback: Box<dyn HaloStreamCallback>,
    vault: Arc<VaultStore>,
    base_halo: Mutex<HaloAnnotation>,
}

impl StreamSink for FfiStreamBridge {
    fn on_delta(&self, text_chunk: &str) {
        self.callback.on_delta(text_chunk.to_string());
    }

    fn on_complete(&self, full_text: &str) {
        let mut halo = self.base_halo.lock().clone();
        halo.response_markdown = full_text.to_string();
        let _ = self.vault.upsert_halo(&halo);
        let halo_json = serde_json::to_string(&halo).unwrap_or_else(|_| "{}".into());
        self.callback.on_complete(full_text.to_string(), halo_json);
    }

    fn on_error(&self, err: &SaltError) {
        let code = match err {
            SaltError::PrivacyBlocked { .. } => "PRIVACY_BLOCKED",
            SaltError::Cancelled => "CANCELLED",
            SaltError::SecretNotFound(_) => "SECRET_NOT_FOUND",
            _ => "ROUTER_ERROR",
        };
        self.callback
            .on_error(code.to_string(), redact_sensitive_text(&err.to_string()));
    }
}

/// Handle returned to UI for immediately cancelling an active Salt Halo AI stream.
#[derive(uniffi::Object)]
pub struct HaloCancelHandle {
    token: CancellationToken,
}

#[uniffi::export]
impl HaloCancelHandle {
    pub fn cancel(&self) {
        self.token.cancel();
    }

    pub fn is_cancelled(&self) -> bool {
        self.token.is_cancelled()
    }
}

#[derive(Serialize)]
struct LanNodeInfoJson {
    device_id: String,
    host_ip: String,
    port: u16,
    fingerprint: String,
    pairing_token: String,
    pairing_pin: String,
    pairing_expires_at_utc: i64,
}

#[derive(Serialize)]
struct StrokeIngestResponse {
    stroke_id: String,
    mesh: salt_types::TessellatedMesh,
    render_stroke: salt_types::RenderStroke,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InboxKind {
    QuickText,
    PhotoNote,
    VoiceMemo,
    WebClip,
}

impl From<InboxKind> for salt_types::InboxKind {
    fn from(v: InboxKind) -> Self {
        match v {
            InboxKind::QuickText => salt_types::InboxKind::QuickText,
            InboxKind::PhotoNote => salt_types::InboxKind::PhotoNote,
            InboxKind::VoiceMemo => salt_types::InboxKind::VoiceMemo,
            InboxKind::WebClip => salt_types::InboxKind::WebClip,
        }
    }
}

impl From<salt_types::InboxKind> for InboxKind {
    fn from(v: salt_types::InboxKind) -> Self {
        match v {
            salt_types::InboxKind::QuickText => InboxKind::QuickText,
            salt_types::InboxKind::PhotoNote => InboxKind::PhotoNote,
            salt_types::InboxKind::VoiceMemo => InboxKind::VoiceMemo,
            salt_types::InboxKind::WebClip => InboxKind::WebClip,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InboxCaptureInput {
    pub kind: InboxKind,
    pub text_note: Option<String>,
    pub primary_media_bytes: Option<Vec<u8>>,
    pub primary_media_ext: Option<String>,
    pub thumb_webp_bytes: Option<Vec<u8>>,
    pub audio_duration_ms: u32,
    pub is_private: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InboxItemView {
    pub inbox_id: String,
    pub kind: InboxKind,
    pub created_at_utc: i64,
    pub is_private: bool,
    pub state: String,
    pub text_note: Option<String>,
    pub transcript_text: Option<String>,
    pub summary_text: Option<String>,
    pub auto_tags: Vec<String>,
    pub transcript_status: String,
    pub thumb_asset_path: Option<String>,
    pub primary_asset_path: Option<String>,
    pub audio_duration_ms: u32,
    pub consumed_page_id: Option<String>,
    pub consumed_block_id: Option<String>,
}

impl From<InboxItem> for InboxItemView {
    fn from(it: InboxItem) -> Self {
        let primary_asset_path = match it.kind {
            salt_types::InboxKind::VoiceMemo => it.payload.audio_asset_rel_path,
            _ => it.payload.image_asset_rel_path,
        };
        Self {
            inbox_id: it.inbox_id,
            kind: it.kind.into(),
            created_at_utc: it.created_at_utc,
            is_private: it.is_private,
            state: it.state.as_str().to_string(),
            text_note: it.payload.text_note,
            transcript_text: it.ai_enrichment.transcript_text,
            summary_text: it.ai_enrichment.summary_text,
            auto_tags: it.ai_enrichment.auto_tags,
            transcript_status: it.ai_enrichment.transcript_status.as_str().to_string(),
            thumb_asset_path: it.payload.thumb_asset_rel_path,
            primary_asset_path,
            audio_duration_ms: it.payload.audio_duration_ms,
            consumed_page_id: it.consumption.consumed_page_id,
            consumed_block_id: it.consumption.consumed_block_id,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReviewCardView {
    pub card_id: String,
    pub page_id: String,
    pub anchor_halo_id: Option<String>,
    pub front: String,
    pub back: String,
    pub stability: f64,
    pub difficulty: f64,
    pub reps: u32,
    pub lapses: u32,
    pub state: u8,
    pub last_review_utc: Option<i64>,
    pub due_at_utc: i64,
    pub retrievability: f64,
    pub source_page_title: String,
    pub slice_asset_path: Option<String>,
    pub context_excerpt: Option<String>,
}

impl From<salt_types::ReviewCardView> for ReviewCardView {
    fn from(v: salt_types::ReviewCardView) -> Self {
        Self {
            card_id: v.card_id,
            page_id: v.page_id,
            anchor_halo_id: v.anchor_halo_id,
            front: v.front,
            back: v.back,
            stability: v.stability,
            difficulty: v.difficulty,
            reps: v.reps,
            lapses: v.lapses,
            state: v.state,
            last_review_utc: v.last_review_utc,
            due_at_utc: v.due_at_utc,
            retrievability: v.retrievability,
            source_page_title: v.source_page_title,
            slice_asset_path: v.slice_asset_path,
            context_excerpt: v.context_excerpt,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MicroSessionPlan {
    pub session_id: String,
    pub budget_seconds: u32,
    pub estimated_seconds: u32,
    pub cards: Vec<ReviewCardView>,
    pub total_due_count: u32,
}

impl From<salt_types::MicroSessionPlan> for MicroSessionPlan {
    fn from(p: salt_types::MicroSessionPlan) -> Self {
        Self {
            session_id: p.session_id,
            budget_seconds: p.budget_seconds,
            estimated_seconds: p.estimated_seconds,
            cards: p.cards.into_iter().map(Into::into).collect(),
            total_due_count: p.total_due_count,
        }
    }
}

/// Salt Inbox (盐池) UniFFI service handle (`SaltEngine::inbox()`).
#[derive(uniffi::Object)]
pub struct InboxService {
    vault: Arc<VaultStore>,
    router: Arc<SaltRouter>,
    p2p_node: Arc<Mutex<Option<Arc<LanP2pNode>>>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl InboxService {
    /// Zero-friction capture into Vault-root CRDT `inbox` map in milliseconds.
    pub fn capture_item(&self, input: InboxCaptureInput) -> Result<String, FfiError> {
        let item = self.vault.capture_inbox_item(
            input.kind.into(),
            input.text_note,
            input.primary_media_bytes.as_deref(),
            input.primary_media_ext.as_deref(),
            input.thumb_webp_bytes.as_deref(),
            input.audio_duration_ms,
            input.is_private,
        )?;
        Ok(item.inbox_id)
    }

    pub fn list_items(
        &self,
        include_archived: bool,
        limit: u32,
    ) -> Result<Vec<InboxItemView>, FfiError> {
        let items = self.vault.list_inbox_items(include_archived, limit as usize);
        Ok(items.into_iter().map(Into::into).collect())
    }

    /// Triggers async transcription/OCR/summarization subject to `#private` & `SaltRelay` guard.
    pub async fn enrich_item(&self, inbox_id: String) -> Result<InboxItemView, FfiError> {
        let vault = Arc::clone(&self.vault);
        let router = Arc::clone(&self.router);
        let p2p_slot = Arc::clone(&self.p2p_node);

        GLOBAL_RUNTIME
            .spawn(async move { enrich_inbox_item_impl(vault, router, p2p_slot, inbox_id).await })
            .await
            .map_err(|e| FfiError::Internal {
                detail: format!("Enrichment task failed: {e}"),
            })?
    }

    /// Marks an inbox item as consumed/archived and binds bidirectional provenance to target page/block.
    pub fn consume_into_page(
        &self,
        inbox_id: String,
        target_page_id: String,
        target_block_id: String,
    ) -> Result<(), FfiError> {
        self.vault
            .consume_inbox_into_page(&inbox_id, &target_page_id, &target_block_id)?;
        Ok(())
    }

    pub fn set_private(
        &self,
        inbox_id: String,
        is_private: bool,
    ) -> Result<InboxItemView, FfiError> {
        let updated = self.vault.set_inbox_private(&inbox_id, is_private)?;
        Ok(updated.into())
    }

    pub fn delete_item(&self, inbox_id: String) -> Result<(), FfiError> {
        self.vault.delete_inbox_item(&inbox_id)?;
        Ok(())
    }
}

/// FSRS micro-session scheduler UniFFI service handle (`SaltEngine::intel()`).
#[derive(uniffi::Object)]
pub struct IntelService {
    vault: Arc<VaultStore>,
    intel: Arc<IntelEngine>,
}

#[uniffi::export(async_runtime = "tokio")]
impl IntelService {
    pub async fn build_micro_review_session(
        &self,
        budget_seconds: u32,
        energy_level: u8,
        rescue_mode: bool,
    ) -> Result<MicroSessionPlan, FfiError> {
        let vault = Arc::clone(&self.vault);
        let intel = Arc::clone(&self.intel);
        GLOBAL_RUNTIME
            .spawn_blocking(move || {
                intel.build_micro_review_plan(&vault, budget_seconds, energy_level, rescue_mode, None)
            })
            .await
            .map_err(|e| FfiError::Internal {
                detail: format!("Micro session task failed: {e}"),
            })?
            .map(Into::into)
            .map_err(Into::into)
    }
}

/// Main SaltNote core facade exposed to Android & Desktop Compose Multiplatform apps.
#[derive(uniffi::Object)]
pub struct SaltEngine {
    vault: Arc<VaultStore>,
    intel: Arc<IntelEngine>,
    router: Arc<SaltRouter>,
    secrets: Arc<dyn SecretResolver>,
    spatial_cache: Arc<RwLock<HashMap<(String, String), CanvasSpatialIndex>>>,
    p2p_node: Arc<Mutex<Option<Arc<LanP2pNode>>>>,
}

#[uniffi::export]
impl SaltEngine {
    #[uniffi::constructor]
    pub fn open(
        vault_path: String,
        device_name: String,
        secret_store: Box<dyn PlatformSecretStore>,
    ) -> Result<Arc<Self>, FfiError> {
        let vault = Arc::new(VaultStore::open(&vault_path, Some(device_name))?);
        let intel = Arc::new(IntelEngine::open(vault.paths().sqlite_path())?);
        intel.rebuild_from_vault(&vault)?;

        let _guard = GLOBAL_RUNTIME.enter();
        intel.spawn_background_listener(Arc::clone(&vault));

        let secrets: Arc<dyn SecretResolver> = Arc::new(FfiSecretBridge {
            inner: secret_store,
        });
        let router = Arc::new(SaltRouter::new(Arc::clone(&secrets))?);

        Ok(Arc::new(Self {
            vault,
            intel,
            router,
            secrets,
            spatial_cache: Arc::new(RwLock::new(HashMap::new())),
            p2p_node: Arc::new(Mutex::new(None)),
        }))
    }

    pub fn inbox(&self) -> Arc<InboxService> {
        Arc::new(InboxService {
            vault: Arc::clone(&self.vault),
            router: Arc::clone(&self.router),
            p2p_node: Arc::clone(&self.p2p_node),
        })
    }

    pub fn intel(&self) -> Arc<IntelService> {
        Arc::new(IntelService {
            vault: Arc::clone(&self.vault),
            intel: Arc::clone(&self.intel),
        })
    }

    // ------------------------------------------------------------------------
    // Vault & Page Operations
    // ------------------------------------------------------------------------

    pub fn get_sync_revision(&self) -> u64 {
        self.vault.sync_revision()
    }

    pub fn get_inbox_sync_revision(&self) -> u64 {
        self.vault.inbox_sync_revision()
    }

    pub fn get_device_state_json(&self) -> String {
        serde_json::to_string(&self.vault.device_state()).unwrap_or_else(|_| "{}".into())
    }

    pub fn list_pages_json(&self) -> String {
        serde_json::to_string(&self.vault.list_pages()).unwrap_or_else(|_| "[]".into())
    }

    pub fn ensure_welcome_page_json(&self) -> Result<String, FfiError> {
        let meta = self.vault.ensure_welcome_page()?;
        self.intel.rebuild_from_vault(&self.vault)?;
        Ok(serde_json::to_string(&meta).unwrap_or_else(|_| "{}".into()))
    }

    pub fn create_page(
        &self,
        title: String,
        mode: String,
        is_private: bool,
        tags_json: String,
        initial_markdown: String,
    ) -> Result<String, FfiError> {
        let page_mode = parse_page_mode(&mode);
        let tags: Vec<String> = serde_json::from_str(&tags_json).unwrap_or_default();
        let meta = self
            .vault
            .create_page(title, page_mode, is_private, tags, &initial_markdown)?;
        let _ = self.intel.index_page(&meta, &initial_markdown);
        Ok(serde_json::to_string(&meta).unwrap_or_else(|_| "{}".into()))
    }

    pub fn get_page_snapshot_json(&self, page_id: String) -> Result<String, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        Ok(serde_json::to_string(&snap).unwrap_or_else(|_| "{}".into()))
    }

    pub fn update_page_meta(
        &self,
        page_id: String,
        title: Option<String>,
        mode: Option<String>,
        is_private: Option<bool>,
        tags_json: Option<String>,
    ) -> Result<String, FfiError> {
        let page_mode = mode.as_deref().map(parse_page_mode);
        let tags = tags_json.and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok());
        let meta = self
            .vault
            .update_page_meta(&page_id, title, page_mode, is_private, tags)?;
        if let Ok(snap) = self.vault.get_page_snapshot(&page_id) {
            let _ = self.intel.index_page(&snap.meta, &snap.body_markdown);
        }
        Ok(serde_json::to_string(&meta).unwrap_or_else(|_| "{}".into()))
    }

    pub fn update_page_body(&self, page_id: String, markdown: String) -> Result<(), FfiError> {
        self.vault.update_page_body(&page_id, &markdown)?;
        if let Ok(snap) = self.vault.get_page_snapshot(&page_id) {
            let _ = self.intel.index_page(&snap.meta, &snap.body_markdown);
        }
        Ok(())
    }

    pub fn delete_page(&self, page_id: String) -> Result<(), FfiError> {
        self.vault.delete_page(&page_id)?;
        let _ = self.intel.remove_page(&page_id);
        self.spatial_cache
            .write()
            .retain(|(pid, _), _| pid != &page_id);
        Ok(())
    }

    pub fn flush_deltas(&self) -> Result<u32, FfiError> {
        let entries = self.vault.flush_all_dirty_deltas()?;
        Ok(entries.len() as u32)
    }

    pub fn export_markdown_backup(&self, target_dir: String) -> Result<u32, FfiError> {
        let count = self.vault.export_markdown_backup(target_dir)?;
        Ok(count)
    }

    // ------------------------------------------------------------------------
    // Low-Latency Canvas & Ink Pipeline
    // ------------------------------------------------------------------------

    /// Ingests raw 16-byte little-endian point buffer (`f32 x, f32 y, f32 pressure, u32 dt_ms`),
    /// runs One-Euro Filter + delta compression + Loro WAL commit + R-Tree indexing + tessellation.
    pub fn ingest_raw_stroke(
        &self,
        page_id: String,
        canvas_id: String,
        brush_kind: String,
        color_argb: u32,
        base_width: f32,
        raw_points_le: Vec<u8>,
    ) -> Result<String, FfiError> {
        let brush = parse_brush_kind(&brush_kind);
        let (stroke, mesh, render_stroke) =
            ingest_raw_stroke_bytes(&raw_points_le, brush, color_argb, base_width, None)?;
        self.vault.add_stroke(&page_id, &canvas_id, &stroke)?;

        let mut cache = self.spatial_cache.write();
        if let Some(index) = cache.get_mut(&(page_id.clone(), canvas_id.clone())) {
            index.insert_stroke(stroke.stroke_id.clone(), stroke.bbox);
        }

        let resp = StrokeIngestResponse {
            stroke_id: stroke.stroke_id,
            mesh,
            render_stroke,
        };
        Ok(serde_json::to_string(&resp).unwrap_or_else(|_| "{}".into()))
    }

    /// Queries viewport-visible strokes via R-Tree and returns WYSIWYG high-precision vector strokes.
    pub fn get_canvas_strokes(
        &self,
        page_id: String,
        canvas_id: String,
        min_x: f32,
        min_y: f32,
        max_x: f32,
        max_y: f32,
    ) -> Result<String, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let Some(canvas) = snap.canvases.into_iter().find(|c| c.canvas_id == canvas_id) else {
            return Ok("[]".into());
        };

        let index = self.ensure_spatial_index(&page_id, &canvas);
        let viewport = RectF {
            min_x,
            min_y,
            max_x,
            max_y,
        };
        let visible_ids: std::collections::HashSet<String> = index
            .query_rect(viewport)
            .into_iter()
            .map(|item| item.id.clone())
            .collect();

        let transforms: HashMap<&str, &StrokeTransform> = canvas
            .transforms
            .iter()
            .map(|t| (t.stroke_id.as_str(), t))
            .collect();

        let mut strokes = Vec::new();
        for stroke in &canvas.strokes {
            if !visible_ids.contains(&stroke.stroke_id) {
                continue;
            }
            let tr = transforms.get(stroke.stroke_id.as_str()).copied();
            if tr.map(|t| t.deleted).unwrap_or(false) {
                continue;
            }
            strokes.push(build_render_stroke(stroke, tr));
        }

        Ok(serde_json::to_string(&strokes).unwrap_or_else(|_| "[]".into()))
    }

    /// Queries viewport-visible strokes via R-Tree and returns LOD-tessellated triangle strip meshes.
    pub fn get_canvas_meshes(
        &self,
        page_id: String,
        canvas_id: String,
        min_x: f32,
        min_y: f32,
        max_x: f32,
        max_y: f32,
        lod_zoom: f32,
    ) -> Result<String, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let Some(canvas) = snap.canvases.into_iter().find(|c| c.canvas_id == canvas_id) else {
            return Ok("[]".into());
        };

        let index = self.ensure_spatial_index(&page_id, &canvas);
        let viewport = RectF {
            min_x,
            min_y,
            max_x,
            max_y,
        };
        let visible_ids: std::collections::HashSet<String> = index
            .query_rect(viewport)
            .into_iter()
            .map(|item| item.id.clone())
            .collect();

        let transforms: HashMap<&str, &StrokeTransform> = canvas
            .transforms
            .iter()
            .map(|t| (t.stroke_id.as_str(), t))
            .collect();

        let mut meshes = Vec::new();
        for stroke in &canvas.strokes {
            if !visible_ids.contains(&stroke.stroke_id) {
                continue;
            }
            let tr = transforms.get(stroke.stroke_id.as_str()).copied();
            if tr.map(|t| t.deleted).unwrap_or(false) {
                continue;
            }
            meshes.push(tessellate_stroke(stroke, tr, lod_zoom));
        }

        Ok(serde_json::to_string(&meshes).unwrap_or_else(|_| "[]".into()))
    }

    pub fn erase_strokes_at(
        &self,
        page_id: String,
        canvas_id: String,
        x: f32,
        y: f32,
        radius: f32,
    ) -> Result<Vec<String>, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let Some(canvas) = snap.canvases.into_iter().find(|c| c.canvas_id == canvas_id) else {
            return Ok(Vec::new());
        };
        let index = self.ensure_spatial_index(&page_id, &canvas);
        let candidate_ids: std::collections::HashSet<String> = index
            .query_eraser_hits(x, y, radius)
            .into_iter()
            .collect();
        if candidate_ids.is_empty() {
            return Ok(Vec::new());
        }

        let transforms: HashMap<&str, &StrokeTransform> = canvas
            .transforms
            .iter()
            .map(|t| (t.stroke_id.as_str(), t))
            .collect();

        let mut hits = Vec::new();
        for stroke in &canvas.strokes {
            if !candidate_ids.contains(&stroke.stroke_id) {
                continue;
            }
            let tr = transforms.get(stroke.stroke_id.as_str()).copied();
            if tr.map(|t| t.deleted).unwrap_or(false) {
                continue;
            }
            let render = build_render_stroke(stroke, tr);
            let effective_r = radius + (render.base_width * 0.5).max(2.0);
            if stroke_hits_circle(&render.points_xyp, x, y, effective_r) {
                hits.push(stroke.stroke_id.clone());
            }
        }

        let now = now_utc_ms();
        for sid in &hits {
            self.vault.upsert_stroke_transform(
                &page_id,
                &canvas_id,
                &StrokeTransform {
                    stroke_id: sid.clone(),
                    tx: 0.0,
                    ty: 0.0,
                    scale_x: 1.0,
                    scale_y: 1.0,
                    deleted: true,
                    updated_at_utc: now,
                },
            )?;
        }
        if !hits.is_empty() {
            self.spatial_cache
                .write()
                .remove(&(page_id, canvas_id));
        }
        Ok(hits)
    }

    pub fn insert_canvas_image(
        &self,
        page_id: String,
        canvas_id: String,
        raw_image_bytes: Vec<u8>,
        x: f32,
        y: f32,
        max_width: f32,
    ) -> Result<String, FfiError> {
        let (hash, rel_path, webp_bytes, w, h) = prepare_image_asset_webp(&raw_image_bytes)?;
        self.vault.store_asset_bytes(&rel_path, &webp_bytes)?;

        let fw = (w.max(1)) as f32;
        let fh = (h.max(1)) as f32;
        let max_dim = max_width.clamp(120.0, 480.0);
        let long_edge = fw.max(fh);
        let scale = if long_edge > max_dim {
            max_dim / long_edge
        } else if long_edge < 120.0 {
            160.0 / long_edge
        } else {
            1.0
        };
        let display_w = (fw * scale).max(48.0);
        let display_h = (fh * scale).max(48.0);

        let node = CanvasImageNode {
            image_id: new_ulid(),
            asset_hash: hash,
            asset_rel_path: rel_path,
            x,
            y,
            width: display_w,
            height: display_h,
            deleted: false,
            updated_at_utc: now_utc_ms(),
        };
        self.vault.upsert_canvas_image(&page_id, &canvas_id, &node)?;
        self.spatial_cache
            .write()
            .remove(&(page_id, canvas_id));

        Ok(serde_json::to_string(&node).unwrap_or_else(|_| "{}".into()))
    }

    pub fn update_canvas_image_transform(
        &self,
        page_id: String,
        canvas_id: String,
        image_id: String,
        x: f32,
        y: f32,
        width: f32,
        height: f32,
        deleted: bool,
    ) -> Result<String, FfiError> {
        let updated = self.vault.update_canvas_image_transform(
            &page_id, &canvas_id, &image_id, x, y, width, height, deleted,
        )?;
        self.spatial_cache
            .write()
            .remove(&(page_id, canvas_id));
        Ok(serde_json::to_string(&updated).unwrap_or_else(|_| "{}".into()))
    }

    // ------------------------------------------------------------------------
    // Salt Halo (随手圈) & BYOK Multimodal AI
    // ------------------------------------------------------------------------

    pub fn get_halo_presets_json(&self) -> Result<String, FfiError> {
        let presets = self.vault.load_halo_presets()?;
        Ok(serde_json::to_string(&presets).unwrap_or_else(|_| "[]".into()))
    }

    pub fn trigger_canvas_halo(
        &self,
        page_id: String,
        canvas_id: String,
        preset_id: String,
        lasso_points_le: Vec<u8>,
        provider_id: Option<String>,
        output_language_hint: Option<String>,
        callback: Box<dyn HaloStreamCallback>,
    ) -> Result<Arc<HaloCancelHandle>, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let presets = self.vault.load_halo_presets()?;
        let preset = presets
            .into_iter()
            .find(|p| p.preset_id == preset_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("Halo preset {preset_id} not found"),
            })?;

        let raw_lasso = decode_raw_points_le(&lasso_points_le)?;
        let poly: Vec<(f32, f32)> = raw_lasso.iter().map(|p| (p.x, p.y)).collect();

        let canvas = snap
            .canvases
            .iter()
            .find(|c| c.canvas_id == canvas_id)
            .cloned()
            .unwrap_or(CanvasSnapshot {
                canvas_id: canvas_id.clone(),
                strokes: vec![],
                transforms: vec![],
                images: vec![],
            });
        let index = self.ensure_spatial_index(&page_id, &canvas);
        let selection = index.resolve_lasso_selection(&poly, 0.12)?;

        let need_webp = preset.vision_policy == VisionPolicy::ForceWebpSlice
            || selection.has_handwriting_or_image;

        let halo_id = new_ulid();
        let mut slice_cache_path = None;
        let mut slice_asset_rel_path = None;
        let webp_slice = if need_webp {
            let dense = snap.meta.mode == PageMode::Study
                || preset.vision_policy == VisionPolicy::ForceWebpSlice;
            let bytes = render_canvas_slice_webp(
                &canvas,
                selection.padded_bbox,
                Some(&self.vault.paths().root),
                dense,
            )?;
            let slice_path = self
                .vault
                .paths()
                .slices_cache_dir()
                .join(format!("{halo_id}.webp"));
            let _ = std::fs::write(&slice_path, &bytes);
            slice_cache_path = Some(slice_path.to_string_lossy().to_string());

            // Persist a content-addressed copy under `assets/` so mobile commute review sees the slice!
            let (_, rel_path) = content_addressed_asset_rel_path(&bytes, "webp");
            let _ = self.vault.store_asset_bytes(&rel_path, &bytes);
            slice_asset_rel_path = Some(rel_path);
            Some(bytes)
        } else {
            None
        };

        let context_markdown = extract_markdown_context(&snap.body_markdown, None);
        let provider = self.resolve_provider(provider_id.as_deref(), webp_slice.is_some())?;
        let output_language = self.resolve_effective_output_language(output_language_hint.as_deref());

        // Enforce privacy guard synchronously too so caller gets immediate error if blocked
        SaltRouter::enforce_privacy_guard(snap.meta.is_private, &provider)?;

        // Smart non-overlapping initial pin placement around padded_bbox
        let (pin_x, pin_y) = pick_non_overlapping_pin(
            selection.padded_bbox,
            &snap.halos,
            &canvas_id,
        );

        let base_halo = HaloAnnotation {
            halo_id: halo_id.clone(),
            page_id: page_id.clone(),
            canvas_id: Some(canvas_id),
            preset_id: preset.preset_id.clone(),
            anchor_bbox: Some(selection.padded_bbox),
            anchor_text_offset: None,
            pin_x: Some(pin_x),
            pin_y: Some(pin_y),
            slice_cache_path,
            slice_asset_rel_path,
            context_excerpt: context_markdown.clone(),
            response_markdown: String::new(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        let _ = self.vault.upsert_halo(&base_halo);

        let cancel_token = CancellationToken::new();
        let req = HaloInferenceRequest {
            page_is_private: snap.meta.is_private,
            preset,
            context_markdown,
            user_prompt_extra: None,
            webp_slice,
            output_language,
        };

        let sink = Arc::new(FfiStreamBridge {
            callback,
            vault: Arc::clone(&self.vault),
            base_halo: Mutex::new(base_halo),
        });
        let router = Arc::clone(&self.router);
        let token_clone = cancel_token.clone();
        GLOBAL_RUNTIME.spawn(async move {
            let _ = router
                .stream_halo(&provider, &req, token_clone, sink)
                .await;
        });

        Ok(Arc::new(HaloCancelHandle {
            token: cancel_token,
        }))
    }

    pub fn trigger_text_halo(
        &self,
        page_id: String,
        preset_id: String,
        anchor_offset: u32,
        selected_text: String,
        provider_id: Option<String>,
        output_language_hint: Option<String>,
        callback: Box<dyn HaloStreamCallback>,
    ) -> Result<Arc<HaloCancelHandle>, FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let presets = self.vault.load_halo_presets()?;
        let preset = presets
            .into_iter()
            .find(|p| p.preset_id == preset_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("Halo preset {preset_id} not found"),
            })?;

        let context_markdown =
            extract_markdown_context(&snap.body_markdown, Some(anchor_offset as usize));
        let provider = self.resolve_provider(provider_id.as_deref(), false)?;
        let output_language = self.resolve_effective_output_language(output_language_hint.as_deref());
        SaltRouter::enforce_privacy_guard(snap.meta.is_private, &provider)?;

        let halo_id = new_ulid();
        let base_halo = HaloAnnotation {
            halo_id,
            page_id: page_id.clone(),
            canvas_id: None,
            preset_id: preset.preset_id.clone(),
            anchor_bbox: None,
            anchor_text_offset: Some(anchor_offset),
            pin_x: None,
            pin_y: None,
            slice_cache_path: None,
            slice_asset_rel_path: None,
            context_excerpt: selected_text.clone(),
            response_markdown: String::new(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        let _ = self.vault.upsert_halo(&base_halo);

        let cancel_token = CancellationToken::new();
        let req = HaloInferenceRequest {
            page_is_private: snap.meta.is_private,
            preset,
            context_markdown,
            user_prompt_extra: Some(format!("Highlighted excerpt:\n{selected_text}")),
            webp_slice: None,
            output_language,
        };

        let sink = Arc::new(FfiStreamBridge {
            callback,
            vault: Arc::clone(&self.vault),
            base_halo: Mutex::new(base_halo),
        });
        let router = Arc::clone(&self.router);
        let token_clone = cancel_token.clone();
        GLOBAL_RUNTIME.spawn(async move {
            let _ = router
                .stream_halo(&provider, &req, token_clone, sink)
                .await;
        });

        Ok(Arc::new(HaloCancelHandle {
            token: cancel_token,
        }))
    }

    /// Directly triggers a Salt Halo preset (OCR / Flashcard / Expand) on a Salt Inbox photo
    /// without pasting it onto a canvas first (v0.2-alpha Section 2.4).
    pub fn trigger_inbox_halo(
        &self,
        inbox_id: String,
        page_id: String,
        preset_id: String,
        provider_id: Option<String>,
        output_language_hint: Option<String>,
        callback: Box<dyn HaloStreamCallback>,
    ) -> Result<Arc<HaloCancelHandle>, FfiError> {
        let item = self.vault.get_inbox_item(&inbox_id)?;
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let presets = self.vault.load_halo_presets()?;
        let preset = presets
            .into_iter()
            .find(|p| p.preset_id == preset_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("Halo preset {preset_id} not found"),
            })?;

        let img_rel = item
            .payload
            .image_asset_rel_path
            .clone()
            .or(item.payload.thumb_asset_rel_path.clone());
        let webp_slice = img_rel
            .as_deref()
            .and_then(|rel| std::fs::read(self.vault.paths().root.join(rel)).ok());

        let effective_private = snap.meta.is_private || item.is_private;
        let provider = self.resolve_provider(provider_id.as_deref(), webp_slice.is_some())?;
        let output_language = self.resolve_effective_output_language(output_language_hint.as_deref());
        SaltRouter::enforce_privacy_guard(effective_private, &provider)?;

        let halo_id = new_ulid();
        let context_markdown = item.payload.text_note.clone().unwrap_or_default();
        let base_halo = HaloAnnotation {
            halo_id,
            page_id: page_id.clone(),
            canvas_id: None,
            preset_id: preset.preset_id.clone(),
            anchor_bbox: None,
            anchor_text_offset: Some(snap.body_markdown.chars().count() as u32),
            pin_x: None,
            pin_y: None,
            slice_cache_path: None,
            slice_asset_rel_path: img_rel,
            context_excerpt: context_markdown.clone(),
            response_markdown: String::new(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        let _ = self.vault.upsert_halo(&base_halo);

        let cancel_token = CancellationToken::new();
        let req = HaloInferenceRequest {
            page_is_private: effective_private,
            preset,
            context_markdown,
            user_prompt_extra: Some("Extracted directly from Salt Inbox item".into()),
            webp_slice,
            output_language,
        };

        let sink = Arc::new(FfiStreamBridge {
            callback,
            vault: Arc::clone(&self.vault),
            base_halo: Mutex::new(base_halo),
        });
        let router = Arc::clone(&self.router);
        let token_clone = cancel_token.clone();
        GLOBAL_RUNTIME.spawn(async move {
            let _ = router
                .stream_halo(&provider, &req, token_clone, sink)
                .await;
        });

        Ok(Arc::new(HaloCancelHandle {
            token: cancel_token,
        }))
    }

    pub fn update_halo_pin(
        &self,
        page_id: String,
        halo_id: String,
        pin_x: f32,
        pin_y: f32,
    ) -> Result<String, FfiError> {
        let updated = self.vault.update_halo_pin(&page_id, &halo_id, pin_x, pin_y)?;
        Ok(serde_json::to_string(&updated).unwrap_or_else(|_| "{}".into()))
    }

    pub fn fuse_halo_into_body(&self, page_id: String, halo_id: String) -> Result<String, FfiError> {
        let updated = self.vault.fuse_halo_into_body(&page_id, &halo_id)?;
        if let Ok(snap) = self.vault.get_page_snapshot(&page_id) {
            let _ = self.intel.index_page(&snap.meta, &snap.body_markdown);
        }
        Ok(updated)
    }

    pub fn toggle_halo_collapsed(
        &self,
        page_id: String,
        halo_id: String,
        collapsed: bool,
    ) -> Result<(), FfiError> {
        let snap = self.vault.get_page_snapshot(&page_id)?;
        let mut halo = snap
            .halos
            .into_iter()
            .find(|h| h.halo_id == halo_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("Halo {halo_id} not found"),
            })?;
        halo.collapsed = collapsed;
        self.vault.upsert_halo(&halo)?;
        Ok(())
    }

    pub fn delete_halo(&self, page_id: String, halo_id: String) -> Result<(), FfiError> {
        self.vault.delete_halo(&page_id, &halo_id)?;
        Ok(())
    }

    pub fn parse_flashcard_drafts_json(&self, raw_output: String) -> String {
        let drafts = parse_flashcard_drafts(&raw_output);
        serde_json::to_string(&drafts).unwrap_or_else(|_| "[]".into())
    }

    // ------------------------------------------------------------------------
    // BYOK Router Configuration & Connectivity Verification
    // ------------------------------------------------------------------------

    pub fn get_router_config_json(&self) -> Result<String, FfiError> {
        let cfg = self.vault.load_router_config()?;
        Ok(serde_json::to_string(&cfg).unwrap_or_else(|_| "{}".into()))
    }

    pub fn save_router_config_json(&self, config_json: String) -> Result<(), FfiError> {
        let cfg: RouterConfig =
            serde_json::from_str(&config_json).map_err(|e| FfiError::InvalidInput {
                detail: e.to_string(),
            })?;
        self.vault.save_router_config(&cfg)?;
        if let Some(node) = self.p2p_node.lock().as_ref() {
            node.set_relay_available(cfg.salt_relay_enabled);
        }
        Ok(())
    }

    pub fn test_provider_connection_json(&self, provider_id: String) -> Result<String, FfiError> {
        let cfg = self.vault.load_router_config()?;
        let provider = cfg
            .providers
            .into_iter()
            .find(|p| p.id == provider_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("Provider {provider_id} not found"),
            })?;
        let res = GLOBAL_RUNTIME.block_on(self.router.test_provider_connection(&provider));
        Ok(serde_json::to_string(&res).unwrap_or_else(|_| "{}".into()))
    }

    // ------------------------------------------------------------------------
    // Local FTS5 Search & FSRS Spaced Repetition
    // ------------------------------------------------------------------------

    pub fn search_notes_json(&self, query: String, limit: u32) -> Result<String, FfiError> {
        let hits = self.intel.search(&query, limit.clamp(1, 100) as usize)?;
        Ok(serde_json::to_string(&hits).unwrap_or_else(|_| "[]".into()))
    }

    pub fn create_flashcard(
        &self,
        page_id: String,
        anchor_halo_id: Option<String>,
        front: String,
        back: String,
    ) -> Result<String, FfiError> {
        let card = FlashCard {
            card_id: new_ulid(),
            page_id,
            anchor_halo_id,
            front,
            back,
            created_at_utc: now_utc_ms(),
            archived: false,
        };
        self.vault.upsert_card(&card)?;
        let (cards, logs) = self.vault.get_all_cards_and_logs();
        let _ = self.intel.replay_and_store_fsrs(&cards, &logs);
        Ok(serde_json::to_string(&card).unwrap_or_else(|_| "{}".into()))
    }

    pub fn update_flashcard(
        &self,
        page_id: String,
        card_id: String,
        front: String,
        back: String,
    ) -> Result<String, FfiError> {
        let updated = self.vault.update_card(&page_id, &card_id, front, back)?;
        let (cards, logs) = self.vault.get_all_cards_and_logs();
        let _ = self.intel.replay_and_store_fsrs(&cards, &logs);
        Ok(serde_json::to_string(&updated).unwrap_or_else(|_| "{}".into()))
    }

    pub fn delete_flashcard(&self, page_id: String, card_id: String) -> Result<(), FfiError> {
        self.vault.delete_card(&page_id, &card_id)?;
        let (cards, logs) = self.vault.get_all_cards_and_logs();
        let _ = self.intel.replay_and_store_fsrs(&cards, &logs);
        Ok(())
    }

    pub fn list_due_cards_json(&self) -> Result<String, FfiError> {
        let due = self.intel.list_due_cards(None)?;
        Ok(serde_json::to_string(&due).unwrap_or_else(|_| "[]".into()))
    }

    pub fn list_all_cards_json(&self) -> Result<String, FfiError> {
        let all = self.intel.list_all_cards()?;
        Ok(serde_json::to_string(&all).unwrap_or_else(|_| "[]".into()))
    }

    pub fn submit_card_review(
        &self,
        page_id: String,
        card_id: String,
        rating: u8,
        duration_ms: u32,
    ) -> Result<String, FfiError> {
        let parsed = ReviewRating::from_u8(rating)?;
        let dev = self.vault.device_state();
        let log = ReviewLog {
            log_id: new_ulid(),
            card_id: card_id.clone(),
            rating: parsed,
            reviewed_at_utc: now_utc_ms(),
            duration_ms,
            device_id: dev.device_id,
        };
        self.vault.append_review_log(&page_id, &log)?;
        let (cards, logs) = self.vault.get_all_cards_and_logs();
        let states = self.intel.replay_and_store_fsrs(&cards, &logs)?;
        let updated = states
            .into_iter()
            .find(|s| s.card_id == card_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: "Card state not found after review".into(),
            })?;
        Ok(serde_json::to_string(&updated).unwrap_or_else(|_| "{}".into()))
    }

    // ------------------------------------------------------------------------
    // Zero-Backend Sync (WebDAV + LAN mDNS/QUIC)
    // ------------------------------------------------------------------------

    pub fn get_webdav_config_json(&self) -> Result<String, FfiError> {
        let cfg = self.vault.load_webdav_config()?;
        Ok(serde_json::to_string(&cfg).unwrap_or_else(|_| "{}".into()))
    }

    pub fn save_webdav_config_json(&self, config_json: String) -> Result<(), FfiError> {
        let cfg: WebDavConfig =
            serde_json::from_str(&config_json).map_err(|e| FfiError::InvalidInput {
                detail: e.to_string(),
            })?;
        self.vault.save_webdav_config(&cfg)?;
        Ok(())
    }

    pub fn sync_webdav_now_json(&self) -> Result<String, FfiError> {
        self.sync_webdav_with_policy_json(false)
    }

    pub fn sync_webdav_with_policy_json(&self, is_metered: bool) -> Result<String, FfiError> {
        let cfg = self.vault.load_webdav_config()?;
        if !cfg.enabled {
            return Err(FfiError::InvalidInput {
                detail: "WebDAV sync is disabled in configuration".into(),
            });
        }
        let client =
            WebDavSyncClient::from_config(&cfg, self.secrets.as_ref(), BackoffSchedule::default())?;
        let policy = if is_metered {
            SyncNetworkPolicy::metered_guard()
        } else {
            SyncNetworkPolicy::default()
        };
        let report = GLOBAL_RUNTIME.block_on(client.sync_once_with_policy(&self.vault, policy))?;
        self.intel.rebuild_from_vault(&self.vault)?;
        self.spatial_cache.write().clear();
        Ok(serde_json::to_string(&report).unwrap_or_else(|_| "{}".into()))
    }

    pub fn get_sync_prefs_json(&self) -> Result<String, FfiError> {
        let prefs = self.vault.load_sync_prefs();
        Ok(serde_json::to_string(&prefs).unwrap_or_else(|_| "{}".into()))
    }

    pub fn save_sync_prefs_json(&self, prefs_json: String) -> Result<(), FfiError> {
        let prefs: LocalSyncPrefs =
            serde_json::from_str(&prefs_json).map_err(|e| FfiError::InvalidInput {
                detail: e.to_string(),
            })?;
        self.vault.save_sync_prefs(&prefs)?;
        Ok(())
    }

    pub fn list_paired_peers_json(&self) -> Result<String, FfiError> {
        let peers = self.vault.load_paired_peers();
        Ok(serde_json::to_string(&peers).unwrap_or_else(|_| "[]".into()))
    }

    pub fn remove_paired_peer(&self, device_id: String) -> Result<(), FfiError> {
        self.vault.remove_paired_peer(&device_id)?;
        Ok(())
    }

    pub fn set_paired_peer_auto_sync(
        &self,
        device_id: String,
        enabled: bool,
    ) -> Result<(), FfiError> {
        self.vault.set_paired_peer_auto_sync(&device_id, enabled)?;
        Ok(())
    }

    pub fn start_lan_p2p(&self, enable_mdns: bool) -> Result<String, FfiError> {
        let _guard = GLOBAL_RUNTIME.enter();
        let mut slot = self.p2p_node.lock();
        if let Some(existing) = slot.as_ref() {
            return Ok(self.build_lan_node_info_json(existing));
        }
        let hook = self.make_remote_sync_hook();
        let relay = self.make_salt_relay_handler();
        let node = Arc::new(LanP2pNode::start_with_relay(
            Arc::clone(&self.vault),
            enable_mdns,
            Some(hook),
            Some(relay),
        )?);
        let json = self.build_lan_node_info_json(&node);
        *slot = Some(node);
        Ok(json)
    }

    pub fn stop_lan_p2p(&self) {
        if let Some(node) = self.p2p_node.lock().take() {
            node.shutdown();
        }
    }

    pub fn rotate_lan_pairing_info_json(&self) -> Result<String, FfiError> {
        let slot = self.p2p_node.lock();
        let node = slot.as_ref().ok_or_else(|| FfiError::InvalidInput {
            detail: "LAN P2P is not running".into(),
        })?;
        let _ = node.rotate_pairing_info();
        Ok(self.build_lan_node_info_json(node))
    }

    pub fn refresh_lan_network_json(&self) -> Result<String, FfiError> {
        let slot = self.p2p_node.lock();
        let node = slot.as_ref().ok_or_else(|| FfiError::InvalidInput {
            detail: "LAN P2P is not running".into(),
        })?;
        let _ = node.refresh_local_ip();
        Ok(self.build_lan_node_info_json(node))
    }

    pub fn list_lan_peers_json(&self) -> String {
        let slot = self.p2p_node.lock();
        match slot.as_ref() {
            Some(node) => {
                serde_json::to_string(&node.discovered_peers()).unwrap_or_else(|_| "[]".into())
            }
            None => "[]".into(),
        }
    }

    pub fn pair_with_lan_peer_json(
        &self,
        host: String,
        port: u16,
        expected_fingerprint: String,
        pairing_secret: String,
    ) -> Result<String, FfiError> {
        let addr: SocketAddr =
            format!("{host}:{port}")
                .parse()
                .map_err(|e: std::net::AddrParseError| FfiError::InvalidInput {
                    detail: e.to_string(),
                })?;

        let node = self.ensure_lan_p2p_node(true)?;
        GLOBAL_RUNTIME.block_on(node.pair_with_peer(
            addr,
            &expected_fingerprint,
            &pairing_secret,
            &self.vault,
        ))?;
        let res =
            GLOBAL_RUNTIME.block_on(node.sync_with_peer(addr, &expected_fingerprint, &self.vault))?;
        if res.received_packets > 0 {
            self.intel.rebuild_from_vault(&self.vault)?;
            self.spatial_cache.write().clear();
        }
        Ok(serde_json::to_string(&res).unwrap_or_else(|_| "{}".into()))
    }

    pub fn sync_with_lan_peer_json(
        &self,
        host: String,
        port: u16,
        expected_fingerprint: String,
    ) -> Result<String, FfiError> {
        let addr: SocketAddr =
            format!("{host}:{port}")
                .parse()
                .map_err(|e: std::net::AddrParseError| FfiError::InvalidInput {
                    detail: e.to_string(),
                })?;

        let node = self.ensure_lan_p2p_node(false)?;
        let res =
            GLOBAL_RUNTIME.block_on(node.sync_with_peer(addr, &expected_fingerprint, &self.vault))?;
        if res.received_packets > 0 {
            self.intel.rebuild_from_vault(&self.vault)?;
            self.spatial_cache.write().clear();
        }
        Ok(serde_json::to_string(&res).unwrap_or_else(|_| "{}".into()))
    }

    pub fn sync_all_paired_lan_peers_json(&self) -> Result<String, FfiError> {
        let node = self.ensure_lan_p2p_node(false)?;
        let report = GLOBAL_RUNTIME.block_on(node.sync_all_paired_peers(&self.vault, 3_000));
        if report.total_received_packets > 0 {
            self.intel.rebuild_from_vault(&self.vault)?;
            self.spatial_cache.write().clear();
        }
        Ok(serde_json::to_string(&report).unwrap_or_else(|_| "{}".into()))
    }

    pub fn redact_log_text(&self, raw: String) -> String {
        redact_sensitive_text(&raw)
    }
}

impl SaltEngine {
    fn ensure_lan_p2p_node(&self, enable_discovery: bool) -> Result<Arc<LanP2pNode>, FfiError> {
        let _guard = GLOBAL_RUNTIME.enter();
        let mut slot = self.p2p_node.lock();
        if let Some(existing) = slot.as_ref() {
            return Ok(Arc::clone(existing));
        }
        let hook = self.make_remote_sync_hook();
        let relay = self.make_salt_relay_handler();
        let node = Arc::new(LanP2pNode::start_with_relay(
            Arc::clone(&self.vault),
            enable_discovery,
            Some(hook),
            Some(relay),
        )?);
        *slot = Some(Arc::clone(&node));
        Ok(node)
    }

    fn build_lan_node_info_json(&self, node: &LanP2pNode) -> String {
        let p = node.pairing_info();
        let info = LanNodeInfoJson {
            device_id: self.vault.device_state().device_id,
            host_ip: node.local_ip(),
            port: node.local_port(),
            fingerprint: node.cert_fingerprint().to_string(),
            pairing_token: p.token,
            pairing_pin: p.pin,
            pairing_expires_at_utc: p.expires_at_utc,
        };
        serde_json::to_string(&info).unwrap_or_else(|_| "{}".into())
    }

    fn make_remote_sync_hook(&self) -> salt_sync::OnRemoteSyncHook {
        let vault = Arc::clone(&self.vault);
        let intel = Arc::clone(&self.intel);
        let spatial = Arc::clone(&self.spatial_cache);
        Arc::new(move |scope: RemoteSyncScope| {
            if scope.pages_changed {
                let _ = intel.rebuild_from_vault(&vault);
                spatial.write().clear();
            }
            if scope.inbox_changed {
                vault.bump_inbox_sync_revision();
            }
            vault.bump_sync_revision();
        })
    }

    fn make_salt_relay_handler(&self) -> SaltRelayHandler {
        let vault = Arc::clone(&self.vault);
        let router = Arc::clone(&self.router);
        Arc::new(move |req: RelayEnrichRequest| {
            let vault = Arc::clone(&vault);
            let router = Arc::clone(&router);
            Box::pin(async move {
                let cfg = vault.load_router_config()?;
                if !cfg.salt_relay_enabled {
                    return Err(SaltError::PrivacyBlocked {
                        endpoint: "salt_relay".into(),
                        reason: "SaltRelay is disabled in desktop settings".into(),
                    });
                }
                let local_provider = find_local_provider_for_kind(&cfg, req.kind)?;
                let whisper_provider = build_local_whisper_provider(&cfg);
                router
                    .enrich_inbox_item(
                        &local_provider,
                        whisper_provider.as_ref(),
                        &InboxEnrichmentRequest {
                            is_private: true,
                            kind: req.kind,
                            text_note: req.text_note,
                            media_bytes: req.media_bytes,
                            media_ext: req.media_ext,
                            output_language: req.output_language,
                        },
                    )
                    .await
            })
        })
    }

    fn ensure_spatial_index(&self, page_id: &str, canvas: &CanvasSnapshot) -> CanvasSpatialIndex {
        let key = (page_id.to_string(), canvas.canvas_id.clone());
        if let Some(existing) = self.spatial_cache.read().get(&key) {
            return existing.clone();
        }
        let built = CanvasSpatialIndex::build_from_snapshot(canvas);
        self.spatial_cache.write().insert(key, built.clone());
        built
    }

    fn resolve_provider(
        &self,
        override_id: Option<&str>,
        needs_vision: bool,
    ) -> Result<salt_types::ProviderConfig, FfiError> {
        let cfg = self.vault.load_router_config()?;
        let target_id = override_id
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| {
                if needs_vision {
                    cfg.default_vision_provider.as_str()
                } else {
                    cfg.default_text_provider.as_str()
                }
            });
        cfg.providers
            .into_iter()
            .find(|p| p.id == target_id)
            .ok_or_else(|| FfiError::NotFound {
                detail: format!("AI provider '{target_id}' is not configured"),
            })
    }

    fn resolve_effective_output_language(&self, ui_hint: Option<&str>) -> Option<String> {
        let cfg = self.vault.load_router_config().ok();
        let ai_pref = cfg
            .as_ref()
            .map(|c| c.ai_output_language.trim())
            .unwrap_or("follow_ui");
        let chosen = if ai_pref.is_empty() || ai_pref.eq_ignore_ascii_case("follow_ui") {
            let ui_pref = cfg
                .as_ref()
                .map(|c| c.ui_language.trim())
                .unwrap_or("system");
            if ui_pref.is_empty() || ui_pref.eq_ignore_ascii_case("system") {
                ui_hint.unwrap_or("").trim().to_string()
            } else {
                ui_pref.to_string()
            }
        } else {
            ai_pref.to_string()
        };
        if chosen.is_empty() {
            None
        } else {
            Some(chosen)
        }
    }
}

fn pick_non_overlapping_pin(
    bbox: RectF,
    existing_halos: &[HaloAnnotation],
    canvas_id: &str,
) -> (f32, f32) {
    let mut px = bbox.max_x;
    let mut py = bbox.min_y;
    let existing_pins: Vec<(f32, f32)> = existing_halos
        .iter()
        .filter(|h| h.canvas_id.as_deref() == Some(canvas_id))
        .filter_map(|h| {
            let x = h.pin_x.or_else(|| h.anchor_bbox.map(|b| b.max_x))?;
            let y = h.pin_y.or_else(|| h.anchor_bbox.map(|b| b.min_y))?;
            Some((x, y))
        })
        .collect();

    for _ in 0..12 {
        let collides = existing_pins
            .iter()
            .any(|&(ex, ey)| (ex - px).hypot(ey - py) < 26.0);
        if !collides {
            break;
        }
        py += 34.0;
        if py > bbox.max_y + 120.0 {
            py = bbox.min_y;
            px += 34.0;
        }
    }
    (px, py)
}

fn parse_page_mode(raw: &str) -> PageMode {
    match raw.to_ascii_lowercase().as_str() {
        "journal" => PageMode::Journal,
        _ => PageMode::Study,
    }
}

fn parse_brush_kind(raw: &str) -> BrushKind {
    match raw.to_ascii_lowercase().as_str() {
        "highlighter" => BrushKind::Highlighter,
        "eraser" => BrushKind::Eraser,
        "halo_amber" => BrushKind::HaloAmber,
        "halo_glacier" => BrushKind::HaloGlacier,
        "halo_mineral" => BrushKind::HaloMineral,
        _ => BrushKind::Pen,
    }
}

fn stroke_hits_circle(points_xyp: &[f32], cx: f32, cy: f32, radius: f32) -> bool {
    let n = points_xyp.len() / 3;
    if n == 0 {
        return false;
    }
    let r2 = radius * radius;
    if n == 1 {
        let dx = points_xyp[0] - cx;
        let dy = points_xyp[1] - cy;
        return dx * dx + dy * dy <= r2;
    }
    for i in 0..(n - 1) {
        let x0 = points_xyp[i * 3];
        let y0 = points_xyp[i * 3 + 1];
        let x1 = points_xyp[(i + 1) * 3];
        let y1 = points_xyp[(i + 1) * 3 + 1];
        let vx = x1 - x0;
        let vy = y1 - y0;
        let len2 = vx * vx + vy * vy;
        let t = if len2 > 1e-6 {
            (((cx - x0) * vx + (cy - y0) * vy) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let px = x0 + t * vx;
        let py = y0 + t * vy;
        let dx = cx - px;
        let dy = cy - py;
        if dx * dx + dy * dy <= r2 {
            return true;
        }
    }
    false
}

fn find_local_provider_for_kind(
    cfg: &RouterConfig,
    kind: salt_types::InboxKind,
) -> SaltResult<ProviderConfig> {
    let preferred_id = match kind {
        salt_types::InboxKind::PhotoNote => cfg.default_vision_provider.as_str(),
        salt_types::InboxKind::VoiceMemo => cfg.default_audio_provider.as_str(),
        _ => cfg.default_text_provider.as_str(),
    };
    if let Some(p) = cfg.providers.iter().find(|p| p.id == preferred_id) {
        if is_local_or_rfc1918_endpoint(&p.base_url) {
            return Ok(p.clone());
        }
    }
    if let Some(local) = cfg
        .providers
        .iter()
        .find(|p| is_local_or_rfc1918_endpoint(&p.base_url))
    {
        return Ok(local.clone());
    }
    Err(SaltError::PrivacyBlocked {
        endpoint: preferred_id.into(),
        reason: "No local/RFC1918 Ollama provider available for #private SaltRelay".into(),
    })
}

fn build_local_whisper_provider(cfg: &RouterConfig) -> Option<ProviderConfig> {
    let url = cfg.local_whisper_url.trim();
    if url.is_empty() || !is_local_or_rfc1918_endpoint(url) {
        return None;
    }
    Some(ProviderConfig {
        id: "whisper-local".into(),
        name: "Local Whisper".into(),
        kind: AiProviderKind::OpenAiCompatible,
        base_url: url.to_string(),
        model: "whisper-1".into(),
        api_key_ref: String::new(),
    })
}

async fn enrich_inbox_item_impl(
    vault: Arc<VaultStore>,
    router: Arc<SaltRouter>,
    p2p_slot: Arc<Mutex<Option<Arc<LanP2pNode>>>>,
    inbox_id: String,
) -> Result<InboxItemView, FfiError> {
    let item = vault.get_inbox_item(&inbox_id)?;
    let cfg = vault.load_router_config()?;

    let (media_rel, media_ext) = match item.kind {
        salt_types::InboxKind::VoiceMemo => (
            item.payload.audio_asset_rel_path.clone(),
            item.payload
                .audio_asset_rel_path
                .as_deref()
                .and_then(|p| p.rsplit('.').next())
                .map(str::to_string)
                .or_else(|| Some("ogg".into())),
        ),
        salt_types::InboxKind::PhotoNote => (
            item.payload.image_asset_rel_path.clone(),
            Some("webp".into()),
        ),
        _ => (None, None),
    };
    let media_bytes = match media_rel {
        Some(rel) => Some(
            std::fs::read(vault.paths().root.join(&rel))
                .map_err(|e| FfiError::Internal { detail: e.to_string() })?,
        ),
        None => None,
    };

    let output_language = {
        let ai_pref = cfg.ai_output_language.trim();
        if ai_pref.is_empty() || ai_pref.eq_ignore_ascii_case("follow_ui") {
            let ui_pref = cfg.ui_language.trim();
            if ui_pref.is_empty() || ui_pref.eq_ignore_ascii_case("system") {
                None
            } else {
                Some(ui_pref.to_string())
            }
        } else {
            Some(ai_pref.to_string())
        }
    };

    if item.is_private {
        // 1. Try direct local RFC1918 endpoint if running on PC or configured to LAN Ollama + Whisper
        let local_provider_opt = find_local_provider_for_kind(&cfg, item.kind).ok();
        let local_whisper_opt = build_local_whisper_provider(&cfg);
        let can_run_direct_local = match item.kind {
            salt_types::InboxKind::VoiceMemo => {
                local_whisper_opt.is_some()
                    || local_provider_opt
                        .as_ref()
                        .map(|p| p.kind != AiProviderKind::OllamaLocal)
                        .unwrap_or(false)
            }
            _ => local_provider_opt.is_some(),
        };

        if can_run_direct_local {
            if let Some(local_p) = local_provider_opt {
                let _ = vault.update_inbox_enrichment(
                    &inbox_id,
                    TranscriptStatus::Processing,
                    None,
                    None,
                    None,
                );
                match router
                    .enrich_inbox_item(
                        &local_p,
                        local_whisper_opt.as_ref(),
                        &InboxEnrichmentRequest {
                            is_private: true,
                            kind: item.kind,
                            text_note: item.payload.text_note.clone(),
                            media_bytes: media_bytes.clone(),
                            media_ext: media_ext.clone(),
                            output_language: output_language.clone(),
                        },
                    )
                    .await
                {
                    Ok(out) => {
                        let updated = vault.update_inbox_enrichment(
                            &inbox_id,
                            TranscriptStatus::Completed,
                            out.transcript_text,
                            out.summary_text,
                            Some(out.auto_tags),
                        )?;
                        return Ok(updated.into());
                    }
                    Err(_) => {
                        // Fall through to LAN relay peer if local endpoint isn't reachable directly
                    }
                }
            }
        }

        // 2. Try paired/discovered LAN SaltRelay peer over pinned QUIC
        let node_opt = p2p_slot.lock().clone();
        if let Some(node) = node_opt {
            if let Some(relay_peer) = node
                .discovered_peers()
                .into_iter()
                .find(|p| p.relay_available && p.is_paired)
            {
                if let Ok(addr) = format!("{}:{}", relay_peer.host, relay_peer.port).parse::<SocketAddr>() {
                    let _ = vault.update_inbox_enrichment(
                        &inbox_id,
                        TranscriptStatus::Processing,
                        None,
                        None,
                        None,
                    );
                    if let Ok(out) = node
                        .relay_enrich_with_peer(
                            addr,
                            &relay_peer.cert_fingerprint,
                            RelayEnrichRequest {
                                relay_request_id: new_ulid(),
                                kind: item.kind,
                                is_private: true,
                                text_note: item.payload.text_note.clone(),
                                media_bytes,
                                media_ext,
                                output_language,
                            },
                        )
                        .await
                    {
                        let updated = vault.update_inbox_enrichment(
                            &inbox_id,
                            TranscriptStatus::Completed,
                            out.transcript_text,
                            out.summary_text,
                            Some(out.auto_tags),
                        )?;
                        return Ok(updated.into());
                    }
                }
            }
        }

        // 3. Privacy-first fallback: NEVER touch cloud API; keep waiting for LAN SaltRelay
        let waiting = vault.update_inbox_enrichment(
            &inbox_id,
            TranscriptStatus::WaitingForLanRelay,
            None,
            None,
            None,
        )?;
        return Ok(waiting.into());
    }

    // Non-private item: route through configured audio/vision/text BYOK provider
    let target_id = match item.kind {
        salt_types::InboxKind::VoiceMemo => cfg.default_audio_provider.as_str(),
        salt_types::InboxKind::PhotoNote => cfg.default_vision_provider.as_str(),
        _ => cfg.default_text_provider.as_str(),
    };
    let provider = cfg
        .providers
        .iter()
        .find(|p| p.id == target_id)
        .or_else(|| cfg.providers.iter().find(|p| p.id == cfg.default_text_provider))
        .cloned()
        .ok_or_else(|| FfiError::NotFound {
            detail: format!("AI provider '{target_id}' is not configured"),
        })?;
    let whisper_opt = build_local_whisper_provider(&cfg);

    let _ = vault.update_inbox_enrichment(
        &inbox_id,
        TranscriptStatus::Processing,
        None,
        None,
        None,
    );
    match router
        .enrich_inbox_item(
            &provider,
            whisper_opt.as_ref(),
            &InboxEnrichmentRequest {
                is_private: false,
                kind: item.kind,
                text_note: item.payload.text_note,
                media_bytes,
                media_ext,
                output_language,
            },
        )
        .await
    {
        Ok(out) => {
            let updated = vault.update_inbox_enrichment(
                &inbox_id,
                TranscriptStatus::Completed,
                out.transcript_text,
                out.summary_text,
                Some(out.auto_tags),
            )?;
            Ok(updated.into())
        }
        Err(e) => {
            let _ = vault.update_inbox_enrichment(
                &inbox_id,
                TranscriptStatus::Failed,
                None,
                None,
                None,
            );
            Err(e.into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::{encode_raw_points_le, RawStrokePoint};

    struct TestSecrets;
    impl PlatformSecretStore for TestSecrets {
        fn get_secret(&self, key_ref: String) -> Result<String, FfiError> {
            Ok(format!("test-secret-{key_ref}"))
        }
    }

    #[test]
    fn ffi_end_to_end_local_workflow() {
        let tmp = std::env::temp_dir().join(format!("saltnote_ffi_e2e_{}", new_ulid()));
        let engine = SaltEngine::open(
            tmp.to_string_lossy().to_string(),
            "UnitTest-PC".into(),
            Box::new(TestSecrets),
        )
        .unwrap();

        let welcome_json = engine.ensure_welcome_page_json().unwrap();
        let meta: salt_types::PageMeta = serde_json::from_str(&welcome_json).unwrap();

        let pts = vec![
            RawStrokePoint { x: 10.0, y: 10.0, pressure: 0.6, dt_ms: 0 },
            RawStrokePoint { x: 40.0, y: 30.0, pressure: 0.8, dt_ms: 4 },
            RawStrokePoint { x: 80.0, y: 50.0, pressure: 0.7, dt_ms: 4 },
        ];
        let raw_le = encode_raw_points_le(&pts);
        let stroke_res = engine
            .ingest_raw_stroke(
                meta.page_id.clone(),
                "main".into(),
                "pen".into(),
                0xFF1A1C1E,
                3.0,
                raw_le,
            )
            .unwrap();
        assert!(stroke_res.contains("vertices_xy"));

        let meshes_json = engine
            .get_canvas_meshes(meta.page_id.clone(), "main".into(), 0.0, 0.0, 200.0, 200.0, 1.0)
            .unwrap();
        assert!(meshes_json.contains("stroke_id"));

        // FTS5 search
        let hits = engine.search_notes_json("SaltNote".into(), 10).unwrap();
        assert!(hits.contains(&meta.page_id));

        // FSRS create + review
        let card_json = engine
            .create_flashcard(meta.page_id.clone(), None, "Q1".into(), "A1".into())
            .unwrap();
        let card: FlashCard = serde_json::from_str(&card_json).unwrap();
        let due_before = engine.list_due_cards_json().unwrap();
        assert!(due_before.contains(&card.card_id));

        let reviewed = engine
            .submit_card_review(meta.page_id.clone(), card.card_id.clone(), 3, 2500)
            .unwrap();
        assert!(reviewed.contains("\"reps\":1"));

        // Update & delete flashcard over FFI
        let edited_card_json = engine
            .update_flashcard(
                meta.page_id.clone(),
                card.card_id.clone(),
                "Q1 Updated".into(),
                "A1 Updated".into(),
            )
            .unwrap();
        assert!(edited_card_json.contains("Q1 Updated"));
        assert!(engine.list_all_cards_json().unwrap().contains("Q1 Updated"));
        engine
            .delete_flashcard(meta.page_id.clone(), card.card_id.clone())
            .unwrap();
        assert!(!engine.list_all_cards_json().unwrap().contains(&card.card_id));

        // Privacy hard-block over FFI
        engine
            .update_page_meta(meta.page_id.clone(), None, None, Some(true), None)
            .unwrap();

        struct NoopCb;
        impl HaloStreamCallback for NoopCb {
            fn on_delta(&self, _chunk: String) {}
            fn on_complete(&self, _full_markdown: String, _halo_json: String) {}
            fn on_error(&self, _code: String, _message: String) {}
        }
        let blocked = engine.trigger_text_halo(
            meta.page_id.clone(),
            "builtin.amber_expand".into(),
            0,
            "secret".into(),
            Some("openai-byok".into()),
            Some("zh-CN".into()),
            Box::new(NoopCb),
        );
        assert!(matches!(blocked, Err(FfiError::PrivacyBlocked { .. })));

        // Halo pin move & image transform via FFI
        let halo = HaloAnnotation {
            halo_id: "h-ffi-1".into(),
            page_id: meta.page_id.clone(),
            canvas_id: Some("main".into()),
            preset_id: "builtin.amber_expand".into(),
            anchor_bbox: Some(RectF { min_x: 0.0, min_y: 0.0, max_x: 50.0, max_y: 50.0 }),
            anchor_text_offset: None,
            pin_x: Some(50.0),
            pin_y: Some(0.0),
            slice_cache_path: None,
            slice_asset_rel_path: None,
            context_excerpt: String::new(),
            response_markdown: "hi".into(),
            collapsed: false,
            fused: false,
            created_at_utc: now_utc_ms(),
        };
        engine.vault.upsert_halo(&halo).unwrap();
        let moved_json = engine
            .update_halo_pin(meta.page_id.clone(), "h-ffi-1".into(), 180.0, 95.0)
            .unwrap();
        assert!(moved_json.contains("180.0"));

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[tokio::test]
    async fn inbox_service_and_intel_service_uniffi_roundtrip() {
        let tmp = std::env::temp_dir().join(format!("saltnote_ffi_v02_{}", new_ulid()));
        let engine = SaltEngine::open(
            tmp.to_string_lossy().to_string(),
            "UnitTest-Phone".into(),
            Box::new(TestSecrets),
        )
        .unwrap();

        let page_json = engine
            .create_page("Linear Algebra".into(), "study".into(), false, "[]".into(), "# Eigenvalues".into())
            .unwrap();
        let page: salt_types::PageMeta = serde_json::from_str(&page_json).unwrap();

        let inbox = engine.inbox();
        let id = inbox
            .capture_item(InboxCaptureInput {
                kind: InboxKind::VoiceMemo,
                text_note: Some("Private voice note".into()),
                primary_media_bytes: Some(b"OGG_BYTES".to_vec()),
                primary_media_ext: Some("ogg".into()),
                thumb_webp_bytes: None,
                audio_duration_ms: 6_200,
                is_private: true,
            })
            .unwrap();
        assert!(id.starts_with("inbx_"));

        let listed = inbox.list_items(false, 20).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].transcript_status, "waiting_for_lan_relay");

        // Enrich on #private offline must stay waiting_for_lan_relay without touching cloud
        let enriched = inbox.enrich_item(id.clone()).await.unwrap();
        assert_eq!(enriched.transcript_status, "waiting_for_lan_relay");

        inbox
            .consume_into_page(id.clone(), page.page_id.clone(), "blk_test".into())
            .unwrap();
        assert!(inbox.list_items(false, 20).unwrap().is_empty());
        assert_eq!(inbox.list_items(true, 20).unwrap()[0].state, "archived");

        engine
            .create_flashcard(page.page_id.clone(), None, "Eigenvalue def?".into(), "Av = lambda v".into())
            .unwrap();
        let plan = engine
            .intel()
            .build_micro_review_session(300, 2, true)
            .await
            .unwrap();
        assert_eq!(plan.budget_seconds, 300);
        assert_eq!(plan.total_due_count, 1);
        assert_eq!(plan.cards.len(), 1);
        assert_eq!(plan.cards[0].source_page_title, "Linear Algebra");

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn ffi_lan_pairing_and_batch_inbox_sync_roundtrip() {
        let base = std::env::temp_dir().join(format!("saltnote_ffi_lan_{}", new_ulid()));
        let phone = SaltEngine::open(
            base.join("phone").to_string_lossy().to_string(),
            "FFI-Phone".into(),
            Box::new(TestSecrets),
        )
        .unwrap();
        let pc = SaltEngine::open(
            base.join("pc").to_string_lossy().to_string(),
            "FFI-PC".into(),
            Box::new(TestSecrets),
        )
        .unwrap();

        let pc_info_json = pc.start_lan_p2p(false).unwrap();
        let pc_info: serde_json::Value = serde_json::from_str(&pc_info_json).unwrap();
        let pc_port = pc_info["port"].as_u64().unwrap() as u16;
        let pc_fp = pc_info["fingerprint"].as_str().unwrap().to_string();
        let pc_token = pc_info["pairing_token"].as_str().unwrap().to_string();

        phone
            .pair_with_lan_peer_json("127.0.0.1".into(), pc_port, pc_fp, pc_token)
            .unwrap();
        assert!(phone.list_paired_peers_json().unwrap().contains("FFI-PC"));
        assert!(pc.list_paired_peers_json().unwrap().contains("FFI-Phone"));

        let inbox_id = phone
            .inbox()
            .capture_item(InboxCaptureInput {
                kind: InboxKind::QuickText,
                text_note: Some("Auto sync via paired batch".into()),
                primary_media_bytes: None,
                primary_media_ext: None,
                thumb_webp_bytes: None,
                audio_duration_ms: 0,
                is_private: false,
            })
            .unwrap();
        assert!(inbox_id.starts_with("inbx_"));

        let batch_json = phone.sync_all_paired_lan_peers_json().unwrap();
        let batch: salt_types::LanBatchSyncReport = serde_json::from_str(&batch_json).unwrap();
        assert_eq!(batch.succeeded_peers, 1);
        assert_eq!(batch.total_sent_packets, 1);
        assert!(pc.get_inbox_sync_revision() >= 1);
        assert_eq!(pc.inbox().list_items(false, 10).unwrap().len(), 1);

        phone.stop_lan_p2p();
        pc.stop_lan_p2p();
        let _ = std::fs::remove_dir_all(&base);
    }
}
