use once_cell::sync::Lazy;
use parking_lot::{Mutex, RwLock};
use salt_canvas::{
    build_render_stroke, extract_markdown_context, ingest_raw_stroke_bytes,
    prepare_image_asset_webp, render_canvas_slice_webp, tessellate_stroke, CanvasSpatialIndex,
};
use salt_crdt::VaultStore;
use salt_intel::IntelEngine;
use salt_router::{parse_flashcard_drafts, HaloInferenceRequest, SaltRouter, SecretResolver, StreamSink};
use salt_sync::{BackoffSchedule, LanP2pNode, WebDavSyncClient};
use salt_types::{
    decode_raw_points_le, new_ulid, now_utc_ms, redact_sensitive_text, BrushKind, CanvasImageNode,
    CanvasSnapshot, FlashCard, HaloAnnotation, PageMode, RectF, ReviewLog, ReviewRating,
    RouterConfig, SaltError, SaltResult, StrokeTransform, VisionPolicy, WebDavConfig,
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
}

#[derive(Serialize)]
struct StrokeIngestResponse {
    stroke_id: String,
    mesh: salt_types::TessellatedMesh,
    render_stroke: salt_types::RenderStroke,
}

/// Main SaltNote core facade exposed to Android & Desktop Compose Multiplatform apps.
#[derive(uniffi::Object)]
pub struct SaltEngine {
    vault: Arc<VaultStore>,
    intel: Arc<IntelEngine>,
    router: Arc<SaltRouter>,
    secrets: Arc<dyn SecretResolver>,
    spatial_cache: Arc<RwLock<HashMap<(String, String), CanvasSpatialIndex>>>,
    p2p_node: Mutex<Option<LanP2pNode>>,
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
            p2p_node: Mutex::new(None),
        }))
    }

    // ------------------------------------------------------------------------
    // Vault & Page Operations
    // ------------------------------------------------------------------------

    pub fn get_sync_revision(&self) -> u64 {
        self.vault.sync_revision()
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

        let aspect = if w > 0 { h as f32 / w as f32 } else { 1.0 };
        let display_w = (w as f32).min(max_width.max(120.0));
        let display_h = display_w * aspect;

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
            Some(bytes)
        } else {
            None
        };

        let context_markdown = extract_markdown_context(&snap.body_markdown, None);
        let provider = self.resolve_provider(provider_id.as_deref(), webp_slice.is_some())?;

        // Enforce privacy guard synchronously too so caller gets immediate error if blocked
        SaltRouter::enforce_privacy_guard(snap.meta.is_private, &provider)?;

        let base_halo = HaloAnnotation {
            halo_id: halo_id.clone(),
            page_id: page_id.clone(),
            canvas_id: Some(canvas_id),
            preset_id: preset.preset_id.clone(),
            anchor_bbox: Some(selection.padded_bbox),
            anchor_text_offset: None,
            slice_cache_path,
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
        SaltRouter::enforce_privacy_guard(snap.meta.is_private, &provider)?;

        let halo_id = new_ulid();
        let base_halo = HaloAnnotation {
            halo_id,
            page_id: page_id.clone(),
            canvas_id: None,
            preset_id: preset.preset_id.clone(),
            anchor_bbox: None,
            anchor_text_offset: Some(anchor_offset),
            slice_cache_path: None,
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
        let cfg = self.vault.load_webdav_config()?;
        if !cfg.enabled {
            return Err(FfiError::InvalidInput {
                detail: "WebDAV sync is disabled in configuration".into(),
            });
        }
        let client =
            WebDavSyncClient::from_config(&cfg, self.secrets.as_ref(), BackoffSchedule::default())?;
        let report = GLOBAL_RUNTIME.block_on(client.sync_once(&self.vault))?;
        self.intel.rebuild_from_vault(&self.vault)?;
        self.spatial_cache.write().clear();
        Ok(serde_json::to_string(&report).unwrap_or_else(|_| "{}".into()))
    }

    pub fn start_lan_p2p(&self, enable_mdns: bool) -> Result<String, FfiError> {
        let _guard = GLOBAL_RUNTIME.enter();
        let mut slot = self.p2p_node.lock();
        if let Some(existing) = slot.as_ref() {
            let info = LanNodeInfoJson {
                device_id: self.vault.device_state().device_id,
                host_ip: existing.local_ip().to_string(),
                port: existing.local_port(),
                fingerprint: existing.cert_fingerprint().to_string(),
            };
            return Ok(serde_json::to_string(&info).unwrap_or_else(|_| "{}".into()));
        }
        let hook = self.make_remote_sync_hook();
        let node = LanP2pNode::start(Arc::clone(&self.vault), enable_mdns, Some(hook))?;
        let info = LanNodeInfoJson {
            device_id: self.vault.device_state().device_id,
            host_ip: node.local_ip().to_string(),
            port: node.local_port(),
            fingerprint: node.cert_fingerprint().to_string(),
        };
        *slot = Some(node);
        Ok(serde_json::to_string(&info).unwrap_or_else(|_| "{}".into()))
    }

    pub fn stop_lan_p2p(&self) {
        if let Some(node) = self.p2p_node.lock().take() {
            node.shutdown();
        }
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

        let res = {
            let _guard = GLOBAL_RUNTIME.enter();
            let mut slot = self.p2p_node.lock();
            if slot.is_none() {
                let hook = self.make_remote_sync_hook();
                *slot = Some(LanP2pNode::start(Arc::clone(&self.vault), false, Some(hook))?);
            }
            let node = slot.as_ref().unwrap();
            GLOBAL_RUNTIME.block_on(node.sync_with_peer(addr, &expected_fingerprint, &self.vault))?
        };
        self.intel.rebuild_from_vault(&self.vault)?;
        self.spatial_cache.write().clear();
        Ok(serde_json::to_string(&res).unwrap_or_else(|_| "{}".into()))
    }

    pub fn redact_log_text(&self, raw: String) -> String {
        redact_sensitive_text(&raw)
    }
}

impl SaltEngine {
    fn make_remote_sync_hook(&self) -> salt_sync::p2p::OnRemoteSyncHook {
        let vault = Arc::clone(&self.vault);
        let intel = Arc::clone(&self.intel);
        let spatial = Arc::clone(&self.spatial_cache);
        Arc::new(move || {
            let _ = intel.rebuild_from_vault(&vault);
            spatial.write().clear();
            vault.bump_sync_revision();
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
            Box::new(NoopCb),
        );
        assert!(matches!(blocked, Err(FfiError::PrivacyBlocked { .. })));

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
