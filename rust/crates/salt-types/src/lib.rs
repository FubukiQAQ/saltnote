use chrono::Utc;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::net::IpAddr;
use thiserror::Error;
use ulid::Ulid;
use url::Url;

/// Core error type shared across all SaltNote Rust crates and exposed over UniFFI.
#[derive(Debug, Error, Clone, Serialize, Deserialize)]
pub enum SaltError {
    #[error("Privacy policy blocked outbound request to {endpoint}: {reason}")]
    PrivacyBlocked { endpoint: String, reason: String },

    #[error("Vault not initialized: {0}")]
    VaultNotInitialized(String),

    #[error("Page not found: {0}")]
    PageNotFound(String),

    #[error("Invalid input: {0}")]
    InvalidInput(String),

    #[error("Secret not found for handle: {0}")]
    SecretNotFound(String),

    #[error("CRDT error: {0}")]
    Crdt(String),

    #[error("IO error: {0}")]
    Io(String),

    #[error("Database error: {0}")]
    Database(String),

    #[error("Network error: {0}")]
    Network(String),

    #[error("Sync error: {0}")]
    Sync(String),

    #[error("Rate limited, retry after {retry_after_secs}s")]
    RateLimited { retry_after_secs: u64 },

    #[error("Operation cancelled")]
    Cancelled,
}

pub type SaltResult<T> = Result<T, SaltError>;

pub fn new_ulid() -> String {
    Ulid::new().to_string()
}

pub fn now_utc_ms() -> i64 {
    Utc::now().timestamp_millis()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex::encode(hasher.finalize())
}

// ============================================================================
// Page & Vault Models
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PageMode {
    Journal,
    Study,
}

impl Default for PageMode {
    fn default() -> Self {
        Self::Study
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageMeta {
    pub page_id: String,
    pub title: String,
    pub mode: PageMode,
    pub is_private: bool,
    pub tags: Vec<String>,
    pub created_at_utc: i64,
    pub updated_at_utc: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageSummary {
    pub meta: PageMeta,
    pub snippet: String,
    pub stroke_count: u32,
    pub card_count: u32,
    pub markdown_file: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageSnapshot {
    pub meta: PageMeta,
    pub body_markdown: String,
    pub canvases: Vec<CanvasSnapshot>,
    pub halos: Vec<HaloAnnotation>,
    pub cards: Vec<FlashCard>,
}

// ============================================================================
// Canvas, Ink, and Spatial Geometry Models
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BrushKind {
    Pen,
    Highlighter,
    Eraser,
    HaloAmber,
    HaloGlacier,
    HaloMineral,
}

impl BrushKind {
    pub fn is_halo(self) -> bool {
        matches!(
            self,
            BrushKind::HaloAmber | BrushKind::HaloGlacier | BrushKind::HaloMineral
        )
    }

    pub fn halo_preset_id(self) -> Option<&'static str> {
        match self {
            BrushKind::HaloAmber => Some("builtin.amber_expand"),
            BrushKind::HaloGlacier => Some("builtin.glacier_card"),
            BrushKind::HaloMineral => Some("builtin.mineral_ocr"),
            _ => None,
        }
    }
}

/// Raw 240Hz wet-ink sample transferred from Android/Desktop UI over UniFFI.
/// Wire layout: 16 bytes little-endian (`f32 x`, `f32 y`, `f32 pressure`, `u32 dt_ms`).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RawStrokePoint {
    pub x: f32,
    pub y: f32,
    pub pressure: f32,
    pub dt_ms: u32,
}

pub const RAW_POINT_STRIDE_BYTES: usize = 16;

pub fn encode_raw_points_le(points: &[RawStrokePoint]) -> Vec<u8> {
    let mut out = Vec::with_capacity(points.len() * RAW_POINT_STRIDE_BYTES);
    for p in points {
        out.extend_from_slice(&p.x.to_le_bytes());
        out.extend_from_slice(&p.y.to_le_bytes());
        out.extend_from_slice(&p.pressure.to_le_bytes());
        out.extend_from_slice(&p.dt_ms.to_le_bytes());
    }
    out
}

pub fn decode_raw_points_le(bytes: &[u8]) -> SaltResult<Vec<RawStrokePoint>> {
    if bytes.len() % RAW_POINT_STRIDE_BYTES != 0 {
        return Err(SaltError::InvalidInput(format!(
            "Raw stroke buffer length {} is not a multiple of {} bytes",
            bytes.len(),
            RAW_POINT_STRIDE_BYTES
        )));
    }
    let count = bytes.len() / RAW_POINT_STRIDE_BYTES;
    let mut out = Vec::with_capacity(count);
    for chunk in bytes.chunks_exact(RAW_POINT_STRIDE_BYTES) {
        let x = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        let y = f32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]);
        let pressure = f32::from_le_bytes([chunk[8], chunk[9], chunk[10], chunk[11]]);
        let dt_ms = u32::from_le_bytes([chunk[12], chunk[13], chunk[14], chunk[15]]);
        if !x.is_finite() || !y.is_finite() || !pressure.is_finite() {
            return Err(SaltError::InvalidInput(
                "Non-finite floating point value in stroke buffer".into(),
            ));
        }
        out.push(RawStrokePoint {
            x,
            y,
            pressure: pressure.clamp(0.0, 1.0),
            dt_ms,
        });
    }
    Ok(out)
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RectF {
    pub min_x: f32,
    pub min_y: f32,
    pub max_x: f32,
    pub max_y: f32,
}

impl RectF {
    pub fn from_point(x: f32, y: f32) -> Self {
        Self {
            min_x: x,
            min_y: y,
            max_x: x,
            max_y: y,
        }
    }

    pub fn from_points(points: &[(f32, f32)]) -> Option<Self> {
        let first = *points.first()?;
        let mut r = Self::from_point(first.0, first.1);
        for &(x, y) in &points[1..] {
            r.include_point(x, y);
        }
        Some(r)
    }

    pub fn include_point(&mut self, x: f32, y: f32) {
        if x < self.min_x {
            self.min_x = x;
        }
        if y < self.min_y {
            self.min_y = y;
        }
        if x > self.max_x {
            self.max_x = x;
        }
        if y > self.max_y {
            self.max_y = y;
        }
    }

    pub fn union(&self, other: &RectF) -> RectF {
        RectF {
            min_x: self.min_x.min(other.min_x),
            min_y: self.min_y.min(other.min_y),
            max_x: self.max_x.max(other.max_x),
            max_y: self.max_y.max(other.max_y),
        }
    }

    pub fn width(&self) -> f32 {
        (self.max_x - self.min_x).max(0.0)
    }

    pub fn height(&self) -> f32 {
        (self.max_y - self.min_y).max(0.0)
    }

    pub fn expand_by(&self, pad_x: f32, pad_y: f32) -> RectF {
        RectF {
            min_x: self.min_x - pad_x,
            min_y: self.min_y - pad_y,
            max_x: self.max_x + pad_x,
            max_y: self.max_y + pad_y,
        }
    }

    /// Expand bounding box symmetrically by `ratio` (e.g., 0.12 for 12% padding).
    pub fn expand_ratio(&self, ratio: f32) -> RectF {
        let pad_x = (self.width() * ratio).max(8.0);
        let pad_y = (self.height() * ratio).max(8.0);
        self.expand_by(pad_x, pad_y)
    }

    pub fn intersects(&self, other: &RectF) -> bool {
        self.min_x <= other.max_x
            && self.max_x >= other.min_x
            && self.min_y <= other.max_y
            && self.max_y >= other.min_y
    }

    pub fn translated(&self, tx: f32, ty: f32, sx: f32, sy: f32) -> RectF {
        let x1 = self.min_x * sx + tx;
        let x2 = self.max_x * sx + tx;
        let y1 = self.min_y * sy + ty;
        let y2 = self.max_y * sy + ty;
        RectF {
            min_x: x1.min(x2),
            min_y: y1.min(y2),
            max_x: x1.max(x2),
            max_y: y1.max(y2),
        }
    }
}

/// Compact delta-compressed immutable stroke stored in Loro CRDT.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DeltaStroke {
    pub stroke_id: String,
    pub brush: BrushKind,
    pub color_argb: u32,
    pub base_width: f32,
    pub origin_x: f32,
    pub origin_y: f32,
    /// Fixed-point subpixel quantization factor (e.g. 4.0 => 0.25px resolution).
    pub quant_scale: f32,
    pub dx: Vec<i16>,
    pub dy: Vec<i16>,
    /// Pressure quantized to 0..=255.
    pub pressure_q: Vec<u8>,
    pub dt_ms: Vec<u16>,
    pub bbox: RectF,
    pub created_at_utc: i64,
}

impl DeltaStroke {
    /// Decompress back into canvas-space `(x, y, pressure, dt_ms)` samples.
    pub fn decode_points(&self) -> Vec<RawStrokePoint> {
        let n = self.dx.len();
        let mut pts = Vec::with_capacity(n);
        let mut cur_x = self.origin_x;
        let mut cur_y = self.origin_y;
        let inv = if self.quant_scale > 0.0 {
            1.0 / self.quant_scale
        } else {
            1.0
        };
        for i in 0..n {
            cur_x += (self.dx[i] as f32) * inv;
            cur_y += (self.dy[i] as f32) * inv;
            let p = self.pressure_q.get(i).copied().unwrap_or(128) as f32 / 255.0;
            let dt = self.dt_ms.get(i).copied().unwrap_or(4) as u32;
            pts.push(RawStrokePoint {
                x: cur_x,
                y: cur_y,
                pressure: p,
                dt_ms: dt,
            });
        }
        pts
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StrokeTransform {
    pub stroke_id: String,
    pub tx: f32,
    pub ty: f32,
    pub scale_x: f32,
    pub scale_y: f32,
    pub deleted: bool,
    pub updated_at_utc: i64,
}

impl Default for StrokeTransform {
    fn default() -> Self {
        Self {
            stroke_id: String::new(),
            tx: 0.0,
            ty: 0.0,
            scale_x: 1.0,
            scale_y: 1.0,
            deleted: false,
            updated_at_utc: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CanvasImageNode {
    pub image_id: String,
    pub asset_hash: String,
    pub asset_rel_path: String,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub deleted: bool,
    pub updated_at_utc: i64,
}

impl CanvasImageNode {
    pub fn bbox(&self) -> RectF {
        RectF {
            min_x: self.x,
            min_y: self.y,
            max_x: self.x + self.width,
            max_y: self.y + self.height,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CanvasSnapshot {
    pub canvas_id: String,
    pub strokes: Vec<DeltaStroke>,
    pub transforms: Vec<StrokeTransform>,
    pub images: Vec<CanvasImageNode>,
}

/// Triangle strip mesh generated by `salt-canvas`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TessellatedMesh {
    pub stroke_id: String,
    pub brush: BrushKind,
    pub color_argb: u32,
    /// Interleaved `[x0, y0, x1, y1, ...]` triangle strip vertices.
    pub vertices_xy: Vec<f32>,
    pub bbox: RectF,
}

/// High-precision WYSIWYG vector stroke polyline returned to CMP for native-DPI anti-aliased Bezier rendering.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RenderStroke {
    pub stroke_id: String,
    pub brush: BrushKind,
    pub color_argb: u32,
    pub base_width: f32,
    /// Interleaved `[x0, y0, p0, x1, y1, p1, ...]` in world space.
    pub points_xyp: Vec<f32>,
    pub bbox: RectF,
}

// ============================================================================
// Salt Halo & BYOK AI Router Models
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VisionPolicy {
    /// Use text-only prompt if lasso hits clean text; slice WebP when ink/images are enclosed.
    TextShortCircuitIfClean,
    /// Always capture WebP visual slice (e.g., OCR preset).
    ForceWebpSlice,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HaloOutputMode {
    MarkdownExpand,
    FlashcardJson,
    OcrMarkdown,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HaloPreset {
    pub preset_id: String,
    pub name: String,
    pub color_hex: String,
    pub color_argb: u32,
    pub vision_policy: VisionPolicy,
    pub output_mode: HaloOutputMode,
    pub system_prompt: String,
}

pub fn default_halo_presets() -> Vec<HaloPreset> {
    vec![
        HaloPreset {
            preset_id: "builtin.amber_expand".into(),
            name: "琥珀黄 · 概念扩充".into(),
            color_hex: "#F5C242".into(),
            color_argb: 0xFFF5C242,
            vision_policy: VisionPolicy::TextShortCircuitIfClean,
            output_mode: HaloOutputMode::MarkdownExpand,
            system_prompt: "You are SaltNote's study assistant. Provide a crisp Markdown explanation, intuitive analogy, and key takeaways for the circled note region. Keep it structured and concise.".into(),
        },
        HaloPreset {
            preset_id: "builtin.glacier_card".into(),
            name: "冰川蓝 · 提炼闪卡".into(),
            color_hex: "#4EA8DE".into(),
            color_argb: 0xFF4EA8DE,
            vision_policy: VisionPolicy::TextShortCircuitIfClean,
            output_mode: HaloOutputMode::FlashcardJson,
            system_prompt: "Extract 1 to 3 high-value spaced-repetition flashcards from the circled content. Respond ONLY with a JSON array of objects containing {\"front\": \"...\", \"back\": \"...\"}.".into(),
        },
        HaloPreset {
            preset_id: "builtin.mineral_ocr".into(),
            name: "矿石灰 · 笔迹清洗 OCR".into(),
            color_hex: "#8D99AE".into(),
            color_argb: 0xFF8D99AE,
            vision_policy: VisionPolicy::ForceWebpSlice,
            output_mode: HaloOutputMode::OcrMarkdown,
            system_prompt: "Transcribe the handwritten notes, formulas, diagrams, or whiteboard image accurately into clean Markdown. Preserve math expressions clearly in code/LaTeX blocks.".into(),
        },
    ]
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HaloAnnotation {
    pub halo_id: String,
    pub page_id: String,
    pub canvas_id: Option<String>,
    pub preset_id: String,
    pub anchor_bbox: Option<RectF>,
    pub anchor_text_offset: Option<u32>,
    pub slice_cache_path: Option<String>,
    pub context_excerpt: String,
    pub response_markdown: String,
    pub collapsed: bool,
    pub fused: bool,
    pub created_at_utc: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AiProviderKind {
    OpenAiCompatible,
    Anthropic,
    Gemini,
    OllamaLocal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub id: String,
    pub name: String,
    pub kind: AiProviderKind,
    pub base_url: String,
    pub model: String,
    /// Must be a handle like `vault://openai_primary` (or empty for local unauthenticated Ollama).
    /// Raw API keys are strictly forbidden in `router.json`.
    pub api_key_ref: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RouterConfig {
    pub default_text_provider: String,
    pub default_vision_provider: String,
    pub providers: Vec<ProviderConfig>,
}

impl Default for RouterConfig {
    fn default() -> Self {
        Self {
            default_text_provider: "ollama-local".into(),
            default_vision_provider: "ollama-local".into(),
            providers: vec![
                ProviderConfig {
                    id: "ollama-local".into(),
                    name: "Local Ollama (Offline / Private)".into(),
                    kind: AiProviderKind::OllamaLocal,
                    base_url: "http://127.0.0.1:11434/v1".into(),
                    model: "qwen2.5vl:7b".into(),
                    api_key_ref: String::new(),
                },
                ProviderConfig {
                    id: "openai-byok".into(),
                    name: "OpenAI Compatible (BYOK)".into(),
                    kind: AiProviderKind::OpenAiCompatible,
                    base_url: "https://api.openai.com/v1".into(),
                    model: "gpt-4o-mini".into(),
                    api_key_ref: "vault://openai_api_key".into(),
                },
                ProviderConfig {
                    id: "anthropic-byok".into(),
                    name: "Anthropic Claude (BYOK)".into(),
                    kind: AiProviderKind::Anthropic,
                    base_url: "https://api.anthropic.com".into(),
                    model: "claude-3-5-sonnet-latest".into(),
                    api_key_ref: "vault://anthropic_api_key".into(),
                },
                ProviderConfig {
                    id: "gemini-byok".into(),
                    name: "Google Gemini (BYOK)".into(),
                    kind: AiProviderKind::Gemini,
                    base_url: "https://generativelanguage.googleapis.com".into(),
                    model: "gemini-2.5-flash".into(),
                    api_key_ref: "vault://gemini_api_key".into(),
                },
            ],
        }
    }
}

/// Validates that a `RouterConfig` does not accidentally leak raw API keys before writing to disk.
pub fn validate_router_config_no_plaintext_keys(cfg: &RouterConfig) -> SaltResult<()> {
    for p in &cfg.providers {
        let r = p.api_key_ref.trim();
        if r.is_empty() {
            continue;
        }
        if !r.starts_with("vault://")
            || r.contains("sk-")
            || r.contains("AIza")
            || r.contains("sk-ant-")
        {
            return Err(SaltError::InvalidInput(format!(
                "Provider '{}' api_key_ref must be a 'vault://<key_id>' handle, never a plaintext secret",
                p.id
            )));
        }
    }
    Ok(())
}

/// Checks if a URL resolves to loopback (`localhost`, `127.0.0.0/8`, `::1`) or RFC1918 LAN IP.
pub fn is_local_or_rfc1918_endpoint(raw_url: &str) -> bool {
    let Ok(url) = Url::parse(raw_url) else {
        return false;
    };
    let Some(host) = url.host_str() else {
        return false;
    };
    let host_lower = host.trim_matches(|c| c == '[' || c == ']').to_ascii_lowercase();
    if host_lower == "localhost" || host_lower.ends_with(".local") {
        return true;
    }
    if let Ok(ip) = host_lower.parse::<IpAddr>() {
        return match ip {
            IpAddr::V4(v4) => {
                v4.is_loopback()
                    || v4.is_private() // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
                    || v4.is_link_local()
            }
            IpAddr::V6(v6) => {
                v6.is_loopback() || (v6.segments()[0] & 0xfe00 == 0xfc00) // fc00::/7 ULA
            }
        };
    }
    false
}

/// Redacts API keys, Authorization headers, and sensitive tokens before writing logs or crash reports.
pub fn redact_sensitive_text(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for line in input.lines() {
        let lower = line.to_ascii_lowercase();
        if lower.contains("authorization:")
            || lower.contains("x-api-key:")
            || lower.contains("x-goog-api-key:")
            || lower.contains("api-key:")
        {
            if let Some((k, _)) = line.split_once(':') {
                out.push_str(k);
                out.push_str(": [REDACTED]\n");
                continue;
            }
        }
        let cleaned = redact_inline_tokens(line);
        out.push_str(&cleaned);
        out.push('\n');
    }
    if !input.ends_with('\n') && out.ends_with('\n') {
        out.pop();
    }
    out
}

fn redact_inline_tokens(line: &str) -> String {
    let mut result = String::with_capacity(line.len());
    let chars: Vec<char> = line.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let rest: String = chars[i..].iter().collect();
        let matched_prefix = if rest.starts_with("sk-ant-") {
            Some("sk-ant-")
        } else if rest.starts_with("sk-") {
            Some("sk-")
        } else if rest.starts_with("AIza") {
            Some("AIza")
        } else if rest.starts_with("Bearer ") {
            Some("Bearer ")
        } else {
            None
        };
        if let Some(prefix) = matched_prefix {
            if prefix == "Bearer " {
                result.push_str("Bearer [REDACTED]");
            } else {
                result.push_str("[REDACTED_KEY]");
            }
            i += prefix.len();
            while i < chars.len()
                && (chars[i].is_ascii_alphanumeric() || matches!(chars[i], '-' | '_' | '.'))
            {
                i += 1;
            }
        } else {
            result.push(chars[i]);
            i += 1;
        }
    }
    result
}

// ============================================================================
// Sync Models
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceState {
    pub device_id: String,
    pub device_name: String,
    pub last_seq: u64,
    pub peer_watermarks: BTreeMap<String, u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeltaEntry {
    pub seq: u64,
    pub filename: String,
    pub sha256: String,
    pub size_bytes: u64,
    pub page_id: String,
    pub created_at_utc: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceManifest {
    pub device_id: String,
    pub device_name: String,
    pub latest_seq: u64,
    pub updated_at_utc: i64,
    pub deltas: Vec<DeltaEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WebDavConfig {
    pub endpoint: String,
    pub root_path: String,
    pub username: String,
    pub password_key_ref: String,
    pub enabled: bool,
}

impl Default for WebDavConfig {
    fn default() -> Self {
        Self {
            endpoint: String::new(),
            root_path: "/SaltNoteVault".into(),
            username: String::new(),
            password_key_ref: "vault://webdav_password".into(),
            enabled: false,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncReport {
    pub uploaded_deltas: u32,
    pub downloaded_deltas: u32,
    pub uploaded_assets: u32,
    pub downloaded_assets: u32,
    pub retried_429_count: u32,
    pub errors: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LanPeerInfo {
    pub device_id: String,
    pub device_name: String,
    pub host: String,
    pub port: u16,
    pub cert_fingerprint: String,
}

// ============================================================================
// FSRS & Search Models
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FlashCard {
    pub card_id: String,
    pub page_id: String,
    pub anchor_halo_id: Option<String>,
    pub front: String,
    pub back: String,
    pub created_at_utc: i64,
    pub archived: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum ReviewRating {
    Again = 1,
    Hard = 2,
    Good = 3,
    Easy = 4,
}

impl ReviewRating {
    pub fn from_u8(v: u8) -> SaltResult<Self> {
        match v {
            1 => Ok(Self::Again),
            2 => Ok(Self::Hard),
            3 => Ok(Self::Good),
            4 => Ok(Self::Easy),
            _ => Err(SaltError::InvalidInput(format!(
                "Invalid FSRS rating {v}, expected 1..=4"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReviewLog {
    pub log_id: String,
    pub card_id: String,
    pub rating: ReviewRating,
    pub reviewed_at_utc: i64,
    pub duration_ms: u32,
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FsrsCardState {
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
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SearchHit {
    pub page_id: String,
    pub title: String,
    pub snippet: String,
    pub score: f64,
    pub is_private: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_stroke_binary_roundtrip_16_bytes() {
        let pts = vec![
            RawStrokePoint {
                x: 10.5,
                y: 20.25,
                pressure: 0.75,
                dt_ms: 4,
            },
            RawStrokePoint {
                x: 11.0,
                y: 22.0,
                pressure: 0.9,
                dt_ms: 8,
            },
        ];
        let encoded = encode_raw_points_le(&pts);
        assert_eq!(encoded.len(), 32);
        let decoded = decode_raw_points_le(&encoded).unwrap();
        assert_eq!(decoded, pts);
    }

    #[test]
    fn privacy_endpoint_detection() {
        assert!(is_local_or_rfc1918_endpoint("http://127.0.0.1:11434/v1"));
        assert!(is_local_or_rfc1918_endpoint("http://localhost:11434/v1"));
        assert!(is_local_or_rfc1918_endpoint("http://192.168.1.50:11434/v1"));
        assert!(is_local_or_rfc1918_endpoint("http://10.0.0.8:8080/v1"));
        assert!(is_local_or_rfc1918_endpoint("http://172.16.5.2:11434/v1"));
        assert!(!is_local_or_rfc1918_endpoint("https://api.openai.com/v1"));
        assert!(!is_local_or_rfc1918_endpoint(
            "https://generativelanguage.googleapis.com"
        ));
    }

    #[test]
    fn redact_sensitive_headers_and_keys() {
        let log = "POST /v1/chat\nAuthorization: Bearer sk-live123456\nx-api-key: AIzaSyTestKey999\nbody ok";
        let redacted = redact_sensitive_text(log);
        assert!(!redacted.contains("sk-live123456"));
        assert!(!redacted.contains("AIzaSyTestKey999"));
        assert!(redacted.contains("Authorization: [REDACTED]"));
        assert!(redacted.contains("x-api-key: [REDACTED]"));
    }
}
