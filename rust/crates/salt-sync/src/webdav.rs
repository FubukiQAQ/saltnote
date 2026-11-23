use opendal::raw::HttpClient;
use opendal::{services, ErrorKind, Operator};
use salt_crdt::VaultStore;
use salt_router::SecretResolver;
use salt_types::{
    redact_sensitive_text, sha256_hex, validate_router_config_no_plaintext_keys, DeviceManifest,
    SaltError, SaltResult, SyncNetworkPolicy, SyncReport, WebDavConfig,
};
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::time::Duration;

/// Exponential backoff delays on HTTP 429 / transient connection failures.
/// Production schedule per MVP Section 3.4: 2s -> 4s -> 8s -> 30s.
#[derive(Debug, Clone)]
pub struct BackoffSchedule {
    pub delays_ms: Vec<u64>,
}

impl Default for BackoffSchedule {
    fn default() -> Self {
        Self {
            delays_ms: vec![2_000, 4_000, 8_000, 30_000],
        }
    }
}

impl BackoffSchedule {
    pub fn fast_for_tests() -> Self {
        Self {
            delays_ms: vec![10, 20, 40, 80],
        }
    }
}

/// WebDAV single-writer incremental sync client backed by Apache OpenDAL (`services-webdav`).
pub struct WebDavSyncClient {
    op: Operator,
    backoff: BackoffSchedule,
}

impl WebDavSyncClient {
    pub fn from_config(
        cfg: &WebDavConfig,
        secrets: &dyn SecretResolver,
        backoff: BackoffSchedule,
    ) -> SaltResult<Self> {
        if cfg.endpoint.trim().is_empty() {
            return Err(SaltError::InvalidInput("WebDAV endpoint is empty".into()));
        }
        let password = if cfg.password_key_ref.trim().is_empty() {
            String::new()
        } else {
            secrets.resolve_secret(&cfg.password_key_ref)?
        };

        let reqwest_client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?;

        let mut builder = services::Webdav::default()
            .endpoint(&cfg.endpoint)
            .root(&cfg.root_path)
            .http_client(HttpClient::with(reqwest_client));

        if !cfg.username.is_empty() {
            builder = builder.username(&cfg.username);
        }
        if !password.is_empty() {
            builder = builder.password(&password);
        }

        let op = Operator::new(builder)
            .map_err(|e| SaltError::Sync(redact_sensitive_text(&e.to_string())))?
            .finish();

        Ok(Self { op, backoff })
    }

    /// Executes a complete single-writer upload + remote manifest pull cycle.
    /// Hardcoded ignores `.saltnote/local/**` and guarantees atomic `PUT *.tmp` -> `MOVE` writes.
    pub async fn sync_once(&self, vault: &VaultStore) -> SaltResult<SyncReport> {
        self.sync_once_with_policy(vault, SyncNetworkPolicy::default())
            .await
    }

    /// Executes sync with a caller-supplied `SyncNetworkPolicy` (Metered-Network Guard).
    /// On cellular/metered links (`allow_large_assets == false`), `.delta` packets, 320px thumbnails,
    /// Opus voice memos, and Halo WebP slices sync immediately while >2MB assets wait for Wi-Fi.
    pub async fn sync_once_with_policy(
        &self,
        vault: &VaultStore,
        policy: SyncNetworkPolicy,
    ) -> SaltResult<SyncReport> {
        // Guard: verify router.json contains zero plaintext keys before syncing config
        if let Ok(rcfg) = vault.load_router_config() {
            validate_router_config_no_plaintext_keys(&rcfg)?;
        }

        let mut report = SyncReport::default();

        // 1. Flush local dirty Loro changes to append-only single-writer `.delta` files
        let _ = vault.flush_all_dirty_deltas()?;
        let dev_state = vault.device_state();
        let my_dev_id = dev_state.device_id.clone();

        // Ensure remote directory hierarchy exists
        for dir in [
            ".saltnote/",
            ".saltnote/config/",
            ".saltnote/sync/",
            ".saltnote/sync/manifests/",
            ".saltnote/sync/deltas/",
            &format!(".saltnote/sync/deltas/{my_dev_id}/"),
            "assets/",
        ] {
            let _ = self.retry_op(&mut report, || self.op.create_dir(dir)).await;
        }

        let priority_map: HashMap<String, bool> = vault
            .collect_referenced_assets()
            .into_iter()
            .collect();

        // 2. Upload immutable assets with HEAD existence check & metered-network size guard
        let local_assets_dir = vault.paths().assets_dir();
        if local_assets_dir.exists() {
            for rel_asset in collect_relative_files(&local_assets_dir, "assets")? {
                let local_file = vault.paths().root.join(&rel_asset);
                let file_len = fs::metadata(&local_file).map(|m| m.len()).unwrap_or(0);
                let is_lightweight = *priority_map.get(&rel_asset).unwrap_or(&false);
                if !policy.allow_large_assets && !is_lightweight && file_len > policy.max_asset_bytes {
                    report.deferred_assets += 1;
                    continue;
                }

                let exists = self
                    .retry_op(&mut report, || self.op.exists(&rel_asset))
                    .await
                    .unwrap_or(false);
                if exists {
                    continue;
                }
                if let Some((parent, _)) = rel_asset.rsplit_once('/') {
                    let parent_dir = format!("{parent}/");
                    let _ = self
                        .retry_op(&mut report, || self.op.create_dir(&parent_dir))
                        .await;
                }
                let bytes = fs::read(&local_file).map_err(|e| SaltError::Io(e.to_string()))?;
                self.atomic_put_move(&rel_asset, bytes, &mut report).await?;
                report.uploaded_assets += 1;
            }
        }

        // 3. Upload local single-writer deltas (ONLY into `.saltnote/sync/deltas/{my_dev_id}/`)
        if let Some(my_manifest) = vault.load_manifest(&my_dev_id)? {
            let local_delta_dir = vault.paths().device_deltas_dir(&my_dev_id);
            for entry in &my_manifest.deltas {
                let remote_delta_path =
                    format!(".saltnote/sync/deltas/{my_dev_id}/{}", entry.filename);
                let exists = self
                    .retry_op(&mut report, || self.op.exists(&remote_delta_path))
                    .await
                    .unwrap_or(false);
                if exists {
                    continue;
                }
                let local_file = local_delta_dir.join(&entry.filename);
                if !local_file.exists() {
                    continue;
                }
                let bytes = fs::read(&local_file).map_err(|e| SaltError::Io(e.to_string()))?;
                self.atomic_put_move(&remote_delta_path, bytes, &mut report)
                    .await?;
                report.uploaded_deltas += 1;
            }

            // Atomically publish updated manifest AFTER all deltas are committed
            let manifest_bytes =
                serde_json::to_vec_pretty(&my_manifest).map_err(|e| SaltError::Sync(e.to_string()))?;
            let remote_manifest = format!(".saltnote/sync/manifests/{my_dev_id}.json");
            self.atomic_put_move(&remote_manifest, manifest_bytes, &mut report)
                .await?;
        }

        // 4. Upload safe config files (never contains API keys; never touches `.saltnote/local/`)
        for cfg_name in ["router.json", "halo_presets.json"] {
            let local_p = vault.paths().config_dir().join(cfg_name);
            if local_p.exists() {
                let bytes = fs::read(&local_p).map_err(|e| SaltError::Io(e.to_string()))?;
                let remote_p = format!(".saltnote/config/{cfg_name}");
                let _ = self.atomic_put_move(&remote_p, bytes, &mut report).await;
            }
        }

        // 5. Poll remote manifests and download missing peer `.delta` files in order
        let manifest_entries = self
            .retry_op(&mut report, || self.op.list(".saltnote/sync/manifests/"))
            .await
            .unwrap_or_default();

        for item in manifest_entries {
            let path = item.path().to_string();
            if !path.ends_with(".json") || path.ends_with(".tmp") {
                continue;
            }
            let raw = match self.retry_op(&mut report, || self.op.read(&path)).await {
                Ok(b) => b.to_vec(),
                Err(e) => {
                    report.errors.push(e.to_string());
                    continue;
                }
            };
            let Ok(peer_manifest) = serde_json::from_slice::<DeviceManifest>(&raw) else {
                continue;
            };
            if peer_manifest.device_id == my_dev_id {
                continue;
            }

            let current_watermark = vault
                .device_state()
                .peer_watermarks
                .get(&peer_manifest.device_id)
                .copied()
                .unwrap_or(0);

            let mut missing: Vec<_> = peer_manifest
                .deltas
                .iter()
                .filter(|d| d.seq > current_watermark)
                .collect();
            missing.sort_by_key(|d| d.seq);

            for delta in missing {
                let remote_delta = format!(
                    ".saltnote/sync/deltas/{}/{}",
                    peer_manifest.device_id, delta.filename
                );
                let data = self
                    .retry_op(&mut report, || self.op.read(&remote_delta))
                    .await?
                    .to_vec();

                let actual_hash = sha256_hex(&data);
                if actual_hash != delta.sha256 {
                    return Err(SaltError::Sync(format!(
                        "SHA-256 mismatch on remote delta {}: expected {}, got {}",
                        delta.filename, delta.sha256, actual_hash
                    )));
                }

                vault.import_delta_packet_bytes(&data)?;
                vault.update_peer_watermark(&peer_manifest.device_id, delta.seq)?;
                report.downloaded_deltas += 1;
            }
        }

        // 6. Download any referenced immutable assets (including Halo slices & Inbox media) respecting Metered-Network Guard
        for (rel_asset, is_lightweight) in vault.collect_referenced_assets() {
            let local_p = vault.paths().root.join(&rel_asset);
            if local_p.exists() {
                continue;
            }
            if !policy.allow_large_assets && !is_lightweight {
                if let Ok(meta) = self.retry_op(&mut report, || self.op.stat(&rel_asset)).await {
                    if meta.content_length() > policy.max_asset_bytes {
                        report.deferred_assets += 1;
                        continue;
                    }
                }
            }
            if let Ok(buf) = self.retry_op(&mut report, || self.op.read(&rel_asset)).await {
                let _ = vault.store_asset_bytes(&rel_asset, &buf.to_vec());
                report.downloaded_assets += 1;
            }
        }

        Ok(report)
    }

    /// Atomic WebDAV upload: `PUT <path>.tmp` followed by `MOVE <path>.tmp -> <path>`.
    /// Cleans up `.tmp` on error so corrupted final `.delta` files never linger on server.
    async fn atomic_put_move(
        &self,
        final_path: &str,
        bytes: Vec<u8>,
        report: &mut SyncReport,
    ) -> SaltResult<()> {
        let tmp_path = format!("{final_path}.tmp");
        let put_res = self
            .retry_op(report, || {
                let data = bytes.clone();
                let tp = tmp_path.clone();
                async move { self.op.write(&tp, data).await }
            })
            .await;

        if let Err(e) = put_res {
            let _ = self.op.delete(&tmp_path).await;
            return Err(e);
        }

        let move_res = self
            .retry_op(report, || self.op.rename(&tmp_path, final_path))
            .await;
        if let Err(e) = move_res {
            let _ = self.op.delete(&tmp_path).await;
            return Err(e);
        }
        Ok(())
    }

    async fn retry_op<F, Fut, T>(&self, report: &mut SyncReport, mut f: F) -> SaltResult<T>
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = opendal::Result<T>>,
    {
        let max_attempts = self.backoff.delays_ms.len() + 1;
        for attempt in 0..max_attempts {
            match f().await {
                Ok(val) => return Ok(val),
                Err(err) => {
                    let msg = err.to_string();
                    let is_429 = err.kind() == ErrorKind::RateLimited
                        || msg.contains("429")
                        || msg.to_ascii_lowercase().contains("too many requests");
                    let is_transient = is_429
                        || err.is_temporary()
                        || msg.contains("connection")
                        || msg.contains("broken pipe")
                        || msg.contains("unexpected eof")
                        || msg.contains("reset")
                        || msg.contains("hyper")
                        || msg.contains("send request")
                        || msg.contains("incomplete")
                        || msg.contains("closed before message completed");

                    if is_429 {
                        report.retried_429_count += 1;
                    }
                    if !is_transient || attempt + 1 >= max_attempts {
                        return Err(SaltError::Sync(redact_sensitive_text(&msg)));
                    }
                    let delay_ms = self.backoff.delays_ms[attempt];
                    tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                }
            }
        }
        Err(SaltError::Sync("Retry budget exhausted".into()))
    }
}

fn collect_relative_files(dir: &Path, prefix: &str) -> SaltResult<Vec<String>> {
    let mut out = Vec::new();
    for entry in fs::read_dir(dir).map_err(|e| SaltError::Io(e.to_string()))? {
        let entry = entry.map_err(|e| SaltError::Io(e.to_string()))?;
        let name = entry.file_name().to_string_lossy().to_string();
        let rel = format!("{prefix}/{name}");
        let ty = entry.file_type().map_err(|e| SaltError::Io(e.to_string()))?;
        if ty.is_dir() {
            out.extend(collect_relative_files(&entry.path(), &rel)?);
        } else if !name.ends_with(".tmp") {
            out.push(rel);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_crdt::DeltaPacket;
    use salt_types::{new_ulid, PageMode};
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    struct StaticSecret(String);
    impl SecretResolver for StaticSecret {
        fn resolve_secret(&self, _key_ref: &str) -> SaltResult<String> {
            Ok(self.0.clone())
        }
    }

    /// Minimal HTTP/1.1 Mock WebDAV server with chaos injection (HTTP 429 + mid-stream drops).
    struct ChaosWebDavServer {
        storage_root: std::path::PathBuf,
        url: String,
        handle: tokio::task::JoinHandle<()>,
    }

    impl ChaosWebDavServer {
        async fn start(inject_chaos: bool) -> Self {
            let storage_root =
                std::env::temp_dir().join(format!("saltnote_mock_dav_{}", new_ulid()));
            fs::create_dir_all(&storage_root).unwrap();
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let addr = listener.local_addr().unwrap();
            let root = storage_root.clone();
            let put_counter = Arc::new(AtomicUsize::new(0));

            let handle = tokio::spawn(async move {
                loop {
                    let Ok((mut stream, _)) = listener.accept().await else {
                        break;
                    };
                    let root = root.clone();
                    let counter = put_counter.clone();
                    tokio::spawn(async move {
                        let _ = handle_dav_conn(&mut stream, &root, inject_chaos, &counter).await;
                    });
                }
            });

            Self {
                storage_root,
                url: format!("http://{addr}"),
                handle,
            }
        }
    }

    impl Drop for ChaosWebDavServer {
        fn drop(&mut self) {
            self.handle.abort();
            let _ = fs::remove_dir_all(&self.storage_root);
        }
    }

    async fn handle_dav_conn(
        stream: &mut tokio::net::TcpStream,
        root: &Path,
        inject_chaos: bool,
        put_counter: &AtomicUsize,
    ) -> std::io::Result<()> {
        let mut buf = Vec::new();
        let mut tmp = [0u8; 4096];
        let header_end = loop {
            let n = stream.read(&mut tmp).await?;
            if n == 0 {
                return Ok(());
            }
            buf.extend_from_slice(&tmp[..n]);
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break pos + 4;
            }
        };

        let header_text = String::from_utf8_lossy(&buf[..header_end]).to_string();
        let mut lines = header_text.lines();
        let req_line = lines.next().unwrap_or("");
        let mut parts = req_line.split_whitespace();
        let method = parts.next().unwrap_or("");
        let raw_path = parts.next().unwrap_or("/");
        let path_part = raw_path.split('?').next().unwrap_or("/");

        let mut headers: HashMap<String, String> = HashMap::new();
        for l in lines {
            if let Some((k, v)) = l.split_once(':') {
                headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
            }
        }
        let content_len: usize = headers
            .get("content-length")
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);

        while buf.len() < header_end + content_len {
            let n = stream.read(&mut tmp).await?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&tmp[..n]);
        }
        let body = &buf[header_end..buf.len().min(header_end + content_len)];
        let rel = path_part.trim_start_matches('/');
        let fs_path = root.join(rel);

        match method {
            "MKCOL" => {
                let _ = fs::create_dir_all(&fs_path);
                stream
                    .write_all(b"HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .await?;
            }
            "PUT" => {
                if inject_chaos && rel.ends_with(".delta.tmp") {
                    let c = put_counter.fetch_add(1, Ordering::SeqCst);
                    if c == 0 {
                        // Chaos 1: HTTP 429 Too Many Requests!
                        stream
                            .write_all(b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .await?;
                        return Ok(());
                    }
                    if c == 1 {
                        // Chaos 2: Write half a corrupt file to .tmp and abruptly drop TCP!
                        if let Some(p) = fs_path.parent() {
                            let _ = fs::create_dir_all(p);
                        }
                        let half = &body[..body.len() / 2];
                        let _ = fs::write(&fs_path, half);
                        return Ok(());
                    }
                }
                if let Some(p) = fs_path.parent() {
                    let _ = fs::create_dir_all(p);
                }
                fs::write(&fs_path, body)?;
                stream
                    .write_all(b"HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .await?;
            }
            "MOVE" => {
                let dest = headers.get("destination").cloned().unwrap_or_default();
                let dest_path = dest
                    .split("://")
                    .nth(1)
                    .and_then(|s| s.find('/').map(|i| &s[i..]))
                    .unwrap_or(&dest)
                    .trim_start_matches('/');
                let dst_fs = root.join(dest_path);
                if let Some(p) = dst_fs.parent() {
                    let _ = fs::create_dir_all(p);
                }
                if fs_path.exists() {
                    let _ = fs::rename(&fs_path, &dst_fs);
                    stream
                        .write_all(b"HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .await?;
                } else {
                    stream
                        .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .await?;
                }
            }
            "HEAD" => {
                if fs_path.is_file() {
                    let len = fs::metadata(&fs_path)?.len();
                    let resp = format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n"
                    );
                    stream.write_all(resp.as_bytes()).await?;
                } else {
                    stream
                        .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .await?;
                }
            }
            "GET" => {
                if fs_path.is_file() {
                    let data = fs::read(&fs_path)?;
                    let head = format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        data.len()
                    );
                    stream.write_all(head.as_bytes()).await?;
                    stream.write_all(&data).await?;
                } else {
                    stream
                        .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .await?;
                }
            }
            "DELETE" => {
                let _ = fs::remove_file(&fs_path);
                stream
                    .write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .await?;
            }
            "PROPFIND" => {
                if !fs_path.exists() {
                    stream
                        .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .await?;
                } else {
                    let xml = build_propfind_xml(path_part, &fs_path);
                    let head = format!(
                        "HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        xml.len()
                    );
                    stream.write_all(head.as_bytes()).await?;
                    stream.write_all(xml.as_bytes()).await?;
                }
            }
            _ => {
                stream
                    .write_all(b"HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .await?;
            }
        }
        Ok(())
    }

    fn build_propfind_xml(req_path: &str, fs_path: &Path) -> String {
        let mut entries = String::new();
        let norm_req = if req_path.ends_with('/') || !fs_path.is_dir() {
            req_path.to_string()
        } else {
            format!("{req_path}/")
        };

        let last_mod = "Sat, 21 Nov 2026 08:00:00 GMT";
        if fs_path.is_dir() {
            entries.push_str(&format!(
                "<D:response><D:href>{norm_req}</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:getlastmodified>{last_mod}</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
            ));
            if let Ok(rd) = fs::read_dir(fs_path) {
                for e in rd.flatten() {
                    let name = e.file_name().to_string_lossy().to_string();
                    let is_dir = e.path().is_dir();
                    let href = if is_dir {
                        format!("{norm_req}{name}/")
                    } else {
                        format!("{norm_req}{name}")
                    };
                    let size = e.metadata().map(|m| m.len()).unwrap_or(0);
                    let rt = if is_dir { "<D:collection/>" } else { "" };
                    entries.push_str(&format!(
                        "<D:response><D:href>{href}</D:href><D:propstat><D:prop><D:resourcetype>{rt}</D:resourcetype><D:getcontentlength>{size}</D:getcontentlength><D:getlastmodified>{last_mod}</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                    ));
                }
            }
        } else {
            let size = fs::metadata(fs_path).map(|m| m.len()).unwrap_or(0);
            entries.push_str(&format!(
                "<D:response><D:href>{norm_req}</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>{size}</D:getcontentlength><D:getlastmodified>{last_mod}</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
            ));
        }

        format!(
            r#"<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">{entries}</D:multistatus>"#
        )
    }

    /// MVP Section 6.2 Test 2 + Section 6.3 Security Audit:
    /// Chaos WebDAV with HTTP 429 & dropped connections + zero corrupt `.delta` + zero API keys on server.
    #[tokio::test]
    async fn webdav_chaos_429_and_drop_recovers_cleanly_and_leaks_zero_keys() {
        let server = ChaosWebDavServer::start(true).await;
        let base = std::env::temp_dir().join(format!("saltnote_sync_test_{}", new_ulid()));
        let v1 = VaultStore::open(base.join("dev1"), Some("Tablet".into())).unwrap();
        let v2 = VaultStore::open(base.join("dev2"), Some("Desktop".into())).unwrap();

        let meta = v1
            .create_page(
                "Sync Chaos Note",
                PageMode::Study,
                false,
                vec!["sync".into()],
                "# Sync Test\n\nContent survives 429 & dropped connections.",
            )
            .unwrap();

        let cfg = WebDavConfig {
            endpoint: server.url.clone(),
            root_path: "/vault".into(),
            username: "tester".into(),
            password_key_ref: "vault://webdav_pw".into(),
            enabled: true,
        };
        let secret = StaticSecret("sk-SUPER-SECRET-NEVER-SYNC-AIzaSyTEST".into());
        let client1 =
            WebDavSyncClient::from_config(&cfg, &secret, BackoffSchedule::fast_for_tests())
                .unwrap();
        let r1 = client1.sync_once(&v1).await.unwrap();
        assert!(r1.retried_429_count >= 1, "Expected 429 backoff retry");
        assert_eq!(r1.uploaded_deltas, 1);

        // Verify ZERO corrupt `.delta` files and ZERO leaked API keys anywhere in WebDAV root!
        let mut delta_count = 0;
        let mut stack = vec![server.storage_root.clone()];
        while let Some(dir) = stack.pop() {
            for e in fs::read_dir(&dir).unwrap().flatten() {
                let p = e.path();
                if p.is_dir() {
                    stack.push(p);
                } else {
                    let bytes = fs::read(&p).unwrap();
                    let text = String::from_utf8_lossy(&bytes);
                    assert!(!text.contains("sk-"), "API key leaked into WebDAV: {}", p.display());
                    assert!(!text.contains("AIza"), "Gemini key leaked into WebDAV: {}", p.display());
                    assert!(
                        !p.to_string_lossy().contains(".saltnote\\local")
                            && !p.to_string_lossy().contains(".saltnote/local"),
                        "Local directory must never sync to WebDAV"
                    );
                    if p.extension().and_then(|s| s.to_str()) == Some("delta") {
                        let parsed: Result<DeltaPacket, _> = serde_json::from_slice(&bytes);
                        assert!(parsed.is_ok(), "Final .delta must never be corrupted");
                        delta_count += 1;
                    }
                }
            }
        }
        assert_eq!(delta_count, 1);

        // Second device pulls cleanly from WebDAV and converges bit-identically
        let client2 =
            WebDavSyncClient::from_config(&cfg, &secret, BackoffSchedule::fast_for_tests())
                .unwrap();
        let r2 = client2.sync_once(&v2).await.unwrap();
        assert_eq!(r2.downloaded_deltas, 1);
        assert_eq!(
            v1.compute_page_state_hash(&meta.page_id).unwrap(),
            v2.compute_page_state_hash(&meta.page_id).unwrap()
        );

        let _ = fs::remove_dir_all(&base);
    }

    #[tokio::test]
    async fn webdav_metered_guard_syncs_deltas_thumbs_slices_and_defers_large_assets() {
        let server = ChaosWebDavServer::start(false).await;
        let base = std::env::temp_dir().join(format!("saltnote_metered_dav_{}", new_ulid()));
        let phone = VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap();
        let tablet = VaultStore::open(base.join("tablet"), Some("Tablet".into())).unwrap();

        // Large >2MB photo + lightweight 320px thumb + lightweight Opus memo + Halo slice
        let large_bytes = vec![0x42u8; 2_500_000];
        let thumb_bytes = b"SMALL_THUMB_WEBP".to_vec();
        let opus_bytes = b"SMALL_OPUS_AUDIO".to_vec();
        phone
            .capture_inbox_item(
                salt_types::InboxKind::PhotoNote,
                Some("Large board photo".into()),
                Some(&large_bytes),
                Some("webp"),
                Some(&thumb_bytes),
                0,
                false,
            )
            .unwrap();
        phone
            .capture_inbox_item(
                salt_types::InboxKind::VoiceMemo,
                None,
                Some(&opus_bytes),
                Some("ogg"),
                None,
                8000,
                false,
            )
            .unwrap();

        let cfg = WebDavConfig {
            endpoint: server.url.clone(),
            root_path: "/vault".into(),
            username: "tester".into(),
            password_key_ref: "vault://webdav_pw".into(),
            enabled: true,
        };
        let secret = StaticSecret("pw".into());
        let client_phone =
            WebDavSyncClient::from_config(&cfg, &secret, BackoffSchedule::fast_for_tests())
                .unwrap();

        // Metered cellular upload: uploads deltas + thumb + opus, defers >2MB full photo!
        let r_metered_up = client_phone
            .sync_once_with_policy(&phone, SyncNetworkPolicy::metered_guard())
            .await
            .unwrap();
        assert_eq!(r_metered_up.uploaded_deltas, 1);
        assert_eq!(r_metered_up.uploaded_assets, 2); // thumb + opus
        assert_eq!(r_metered_up.deferred_assets, 1); // 2.5MB full photo deferred

        // Tablet pulls on Wi-Fi: gets inbox metadata + thumb + opus immediately!
        let client_tablet =
            WebDavSyncClient::from_config(&cfg, &secret, BackoffSchedule::fast_for_tests())
                .unwrap();
        let r_tab_pull = client_tablet.sync_once(&tablet).await.unwrap();
        assert_eq!(r_tab_pull.downloaded_deltas, 1);
        assert_eq!(r_tab_pull.downloaded_assets, 2);
        assert_eq!(tablet.list_inbox_items(false, 20).len(), 2);

        // Phone switches to Wi-Fi: full 2.5MB photo uploads cleanly
        let r_wifi_up = client_phone.sync_once(&phone).await.unwrap();
        assert_eq!(r_wifi_up.uploaded_assets, 1);
        assert_eq!(r_wifi_up.deferred_assets, 0);

        let _ = fs::remove_dir_all(&base);
    }
}
