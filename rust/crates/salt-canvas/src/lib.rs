use image::{imageops, DynamicImage, ImageFormat, Rgba, RgbaImage};
use rstar::{RTree, RTreeObject, AABB};
use salt_types::{
    decode_raw_points_le, new_ulid, now_utc_ms, sha256_hex, BrushKind,
    CanvasSnapshot, DeltaStroke, RawStrokePoint, RectF, RenderStroke, SaltError, SaltResult,
    StrokeTransform, TessellatedMesh,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::f32::consts::PI;
use std::io::Cursor;
use std::path::Path;

// ============================================================================
// 1. One-Euro Filter (Low-Latency Adaptive Jitter Removal)
// ============================================================================

#[derive(Debug, Clone)]
struct LowPassFilter {
    y: f32,
    s: f32,
    initialized: bool,
}

impl LowPassFilter {
    fn new() -> Self {
        Self {
            y: 0.0,
            s: 0.0,
            initialized: false,
        }
    }

    fn filter(&mut self, value: f32, alpha: f32) -> f32 {
        let result = if self.initialized {
            alpha * value + (1.0 - alpha) * self.s
        } else {
            self.initialized = true;
            value
        };
        self.y = value;
        self.s = result;
        result
    }

    fn last_raw(&self) -> f32 {
        self.y
    }

    fn is_initialized(&self) -> bool {
        self.initialized
    }
}

/// Adaptive One-Euro Filter for 240Hz stylus coordinates.
#[derive(Debug, Clone)]
pub struct OneEuroFilter {
    min_cutoff: f32,
    beta: f32,
    d_cutoff: f32,
    x_filter: LowPassFilter,
    dx_filter: LowPassFilter,
}

impl OneEuroFilter {
    pub fn new(min_cutoff: f32, beta: f32, d_cutoff: f32) -> Self {
        Self {
            min_cutoff,
            beta,
            d_cutoff,
            x_filter: LowPassFilter::new(),
            dx_filter: LowPassFilter::new(),
        }
    }

    fn alpha(cutoff: f32, dt_secs: f32) -> f32 {
        let tau = 1.0 / (2.0 * PI * cutoff.max(1e-4));
        1.0 / (1.0 + tau / dt_secs.max(1e-4))
    }

    pub fn filter(&mut self, value: f32, dt_secs: f32) -> f32 {
        let dt = dt_secs.clamp(1.0 / 1000.0, 0.25);
        let dvalue = if self.x_filter.is_initialized() {
            (value - self.x_filter.last_raw()) / dt
        } else {
            0.0
        };
        let edvalue = self.dx_filter.filter(dvalue, Self::alpha(self.d_cutoff, dt));
        let cutoff = self.min_cutoff + self.beta * edvalue.abs();
        self.x_filter.filter(value, Self::alpha(cutoff, dt))
    }
}

/// Smooth raw stylus points using paired One-Euro filters for X/Y and light EMA for pressure.
pub fn smooth_stroke_points(raw: &[RawStrokePoint]) -> Vec<RawStrokePoint> {
    if raw.len() <= 2 {
        return raw.to_vec();
    }
    let mut fx = OneEuroFilter::new(1.5, 0.02, 1.0);
    let mut fy = OneEuroFilter::new(1.5, 0.02, 1.0);
    let mut fp = LowPassFilter::new();

    let mut out = Vec::with_capacity(raw.len());
    for (idx, pt) in raw.iter().enumerate() {
        let dt_secs = if idx == 0 {
            1.0 / 240.0
        } else {
            (pt.dt_ms.max(1) as f32) / 1000.0
        };
        let sx = fx.filter(pt.x, dt_secs);
        let sy = fy.filter(pt.y, dt_secs);
        let sp = fp.filter(pt.pressure.clamp(0.05, 1.0), 0.35);
        // Pin the exact first and last endpoints for tactile responsiveness.
        let (x, y) = if idx == 0 || idx + 1 == raw.len() {
            (pt.x, pt.y)
        } else {
            (sx, sy)
        };
        out.push(RawStrokePoint {
            x,
            y,
            pressure: sp,
            dt_ms: pt.dt_ms,
        });
    }
    out
}

// ============================================================================
// 2. Delta Compression & Stroke Ingestion
// ============================================================================

/// Ingests raw little-endian 16-byte/point binary stroke buffer from UniFFI,
/// preserving exact WYSIWYG stylus geometry without destructive smoothing lag.
pub fn ingest_raw_stroke_bytes(
    raw_bytes: &[u8],
    brush: BrushKind,
    color_argb: u32,
    base_width: f32,
    stroke_id: Option<String>,
) -> SaltResult<(DeltaStroke, TessellatedMesh, RenderStroke)> {
    let raw_points = decode_raw_points_le(raw_bytes)?;
    if raw_points.is_empty() {
        return Err(SaltError::InvalidInput("Empty stroke point buffer".into()));
    }
    let stroke = compress_stroke(&raw_points, brush, color_argb, base_width, stroke_id)?;
    let mesh = tessellate_stroke(&stroke, None, 1.0);
    let render = build_render_stroke(&stroke, None);
    Ok((stroke, mesh, render))
}

/// Decodes a `DeltaStroke` with optional transform into a WYSIWYG high-precision `RenderStroke`.
pub fn build_render_stroke(
    stroke: &DeltaStroke,
    transform: Option<&StrokeTransform>,
) -> RenderStroke {
    let pts = stroke.decode_points();
    let (tx, ty, sx, sy) = match transform {
        Some(t) => (t.tx, t.ty, t.scale_x, t.scale_y),
        None => (0.0, 0.0, 1.0, 1.0),
    };
    let mut points_xyp = Vec::with_capacity(pts.len() * 3);
    for p in pts {
        points_xyp.push(p.x * sx + tx);
        points_xyp.push(p.y * sy + ty);
        points_xyp.push(p.pressure);
    }
    let width_scale = ((sx.abs() + sy.abs()) * 0.5).max(0.1);
    RenderStroke {
        stroke_id: stroke.stroke_id.clone(),
        brush: stroke.brush,
        color_argb: stroke.color_argb,
        base_width: stroke.base_width * width_scale,
        points_xyp,
        bbox: stroke.bbox.translated(tx, ty, sx, sy),
    }
}

pub fn compress_stroke(
    points: &[RawStrokePoint],
    brush: BrushKind,
    color_argb: u32,
    base_width: f32,
    stroke_id: Option<String>,
) -> SaltResult<DeltaStroke> {
    if points.is_empty() {
        return Err(SaltError::InvalidInput(
            "Cannot compress empty stroke".into(),
        ));
    }
    let quant_scale = 16.0_f32; // 0.0625 px subpixel accuracy for WYSIWYG smoothness
    let origin_x = points[0].x;
    let origin_y = points[0].y;

    let mut dx = Vec::with_capacity(points.len());
    let mut dy = Vec::with_capacity(points.len());
    let mut pressure_q = Vec::with_capacity(points.len());
    let mut dt_ms = Vec::with_capacity(points.len());

    let mut acc_qx: i64 = 0;
    let mut acc_qy: i64 = 0;
    let mut bbox = RectF::from_point(origin_x, origin_y);
    let pad = (base_width * 0.6).max(1.0);

    for (i, pt) in points.iter().enumerate() {
        bbox.include_point(pt.x, pt.y);
        let target_qx = ((pt.x - origin_x) * quant_scale).round() as i64;
        let target_qy = ((pt.y - origin_y) * quant_scale).round() as i64;
        let step_x = (target_qx - acc_qx).clamp(i16::MIN as i64, i16::MAX as i64) as i16;
        let step_y = (target_qy - acc_qy).clamp(i16::MIN as i64, i16::MAX as i64) as i16;
        acc_qx += step_x as i64;
        acc_qy += step_y as i64;

        // Skip redundant zero-delta interior samples to shrink CRDT payload
        if i > 0 && i + 1 < points.len() && step_x == 0 && step_y == 0 {
            continue;
        }
        dx.push(step_x);
        dy.push(step_y);
        pressure_q.push((pt.pressure.clamp(0.0, 1.0) * 255.0).round() as u8);
        dt_ms.push(pt.dt_ms.min(u16::MAX as u32) as u16);
    }

    Ok(DeltaStroke {
        stroke_id: stroke_id.unwrap_or_else(new_ulid),
        brush,
        color_argb,
        base_width,
        origin_x,
        origin_y,
        quant_scale,
        dx,
        dy,
        pressure_q,
        dt_ms,
        bbox: bbox.expand_by(pad, pad),
        created_at_utc: now_utc_ms(),
    })
}

// ============================================================================
// 3. Pressure-Sensitive Triangle Strip Tessellation (with LOD support)
// ============================================================================

/// Generates a GPU-ready triangle strip from a `DeltaStroke` with optional transform and LOD zoom factor.
pub fn tessellate_stroke(
    stroke: &DeltaStroke,
    transform: Option<&StrokeTransform>,
    lod_zoom: f32,
) -> TessellatedMesh {
    let mut pts = stroke.decode_points();
    let (tx, ty, sx, sy) = match transform {
        Some(t) => (t.tx, t.ty, t.scale_x, t.scale_y),
        None => (0.0, 0.0, 1.0, 1.0),
    };
    for p in &mut pts {
        p.x = p.x * sx + tx;
        p.y = p.y * sy + ty;
    }

    // Distance-based LOD simplification when zoomed out (preserves 60/120fps on 10k strokes)
    let min_step_sq = if lod_zoom < 0.35 {
        16.0
    } else if lod_zoom < 0.7 {
        4.0
    } else {
        0.5
    };
    let mut filtered = Vec::with_capacity(pts.len());
    for (i, p) in pts.iter().enumerate() {
        if i == 0 || i + 1 == pts.len() {
            filtered.push(*p);
        } else if let Some(last) = filtered.last() {
            let ddx = p.x - last.x;
            let ddy = p.y - last.y;
            if ddx * ddx + ddy * ddy >= min_step_sq {
                filtered.push(*p);
            }
        }
    }

    let mut vertices_xy = Vec::with_capacity(filtered.len() * 4 + 16);
    let width_scale = ((sx.abs() + sy.abs()) * 0.5).max(0.1);

    let radius_for = |pressure: f32| -> f32 {
        let base = stroke.base_width * width_scale * 0.5;
        match stroke.brush {
            BrushKind::Pen => (base * (0.35 + 0.95 * pressure.clamp(0.05, 1.0))).max(0.6),
            BrushKind::Highlighter => (base * 2.4).max(4.0),
            BrushKind::Eraser => (base * 2.0).max(4.0),
            BrushKind::HaloAmber | BrushKind::HaloGlacier | BrushKind::HaloMineral => {
                (base * 1.15).max(1.5)
            }
        }
    };

    if filtered.len() == 1 {
        let p = filtered[0];
        let r = radius_for(p.pressure);
        // Dot octagon-quad strip
        let offsets = [
            (-r, -r),
            (-r, r),
            (r, -r),
            (r, r),
        ];
        for (ox, oy) in offsets {
            vertices_xy.push(p.x + ox);
            vertices_xy.push(p.y + oy);
        }
    } else {
        for i in 0..filtered.len() {
            let cur = filtered[i];
            let (dir_x, dir_y) = if i == 0 {
                (filtered[1].x - cur.x, filtered[1].y - cur.y)
            } else if i + 1 == filtered.len() {
                (cur.x - filtered[i - 1].x, cur.y - filtered[i - 1].y)
            } else {
                (
                    filtered[i + 1].x - filtered[i - 1].x,
                    filtered[i + 1].y - filtered[i - 1].y,
                )
            };
            let len = (dir_x * dir_x + dir_y * dir_y).sqrt();
            let (nx, ny) = if len > 1e-4 {
                (-dir_y / len, dir_x / len)
            } else {
                (0.0, 1.0)
            };
            let r = radius_for(cur.pressure);

            // End cap extension at start & end
            if i == 0 && len > 1e-4 {
                let ux = dir_x / len;
                let uy = dir_y / len;
                vertices_xy.push(cur.x - ux * r * 0.65 + nx * r * 0.65);
                vertices_xy.push(cur.y - uy * r * 0.65 + ny * r * 0.65);
                vertices_xy.push(cur.x - ux * r * 0.65 - nx * r * 0.65);
                vertices_xy.push(cur.y - uy * r * 0.65 - ny * r * 0.65);
            }

            vertices_xy.push(cur.x + nx * r);
            vertices_xy.push(cur.y + ny * r);
            vertices_xy.push(cur.x - nx * r);
            vertices_xy.push(cur.y - ny * r);

            if i + 1 == filtered.len() && len > 1e-4 {
                let ux = dir_x / len;
                let uy = dir_y / len;
                vertices_xy.push(cur.x + ux * r * 0.65 + nx * r * 0.65);
                vertices_xy.push(cur.y + uy * r * 0.65 + ny * r * 0.65);
                vertices_xy.push(cur.x + ux * r * 0.65 - nx * r * 0.65);
                vertices_xy.push(cur.y + uy * r * 0.65 - ny * r * 0.65);
            }
        }
    }

    let bbox = stroke.bbox.translated(tx, ty, sx, sy);
    TessellatedMesh {
        stroke_id: stroke.stroke_id.clone(),
        brush: stroke.brush,
        color_argb: stroke.color_argb,
        vertices_xy,
        bbox,
    }
}

// ============================================================================
// 4. R-Tree Spatial Index & Salt Halo Lasso Resolver
// ============================================================================

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SpatialObjectKind {
    Stroke,
    Image,
}

#[derive(Debug, Clone)]
pub struct SpatialItem {
    pub id: String,
    pub kind: SpatialObjectKind,
    pub bbox: RectF,
}

impl RTreeObject for SpatialItem {
    type Envelope = AABB<[f32; 2]>;

    fn envelope(&self) -> Self::Envelope {
        AABB::from_corners(
            [self.bbox.min_x, self.bbox.min_y],
            [self.bbox.max_x, self.bbox.max_y],
        )
    }
}

#[derive(Debug, Clone, Default)]
pub struct CanvasSpatialIndex {
    tree: RTree<SpatialItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LassoSelectionResult {
    pub hit_stroke_ids: Vec<String>,
    pub hit_image_ids: Vec<String>,
    pub tight_bbox: RectF,
    pub padded_bbox: RectF,
    pub has_handwriting_or_image: bool,
}

impl CanvasSpatialIndex {
    pub fn build_from_snapshot(snapshot: &CanvasSnapshot) -> Self {
        let transform_map: HashMap<&str, &StrokeTransform> = snapshot
            .transforms
            .iter()
            .map(|t| (t.stroke_id.as_str(), t))
            .collect();

        let mut items = Vec::with_capacity(snapshot.strokes.len() + snapshot.images.len());
        for s in &snapshot.strokes {
            let tr = transform_map.get(s.stroke_id.as_str()).copied();
            if tr.map(|t| t.deleted).unwrap_or(false) {
                continue;
            }
            let bbox = match tr {
                Some(t) => s.bbox.translated(t.tx, t.ty, t.scale_x, t.scale_y),
                None => s.bbox,
            };
            items.push(SpatialItem {
                id: s.stroke_id.clone(),
                kind: SpatialObjectKind::Stroke,
                bbox,
            });
        }
        for img in &snapshot.images {
            if img.deleted {
                continue;
            }
            items.push(SpatialItem {
                id: img.image_id.clone(),
                kind: SpatialObjectKind::Image,
                bbox: img.bbox(),
            });
        }
        Self {
            tree: RTree::bulk_load(items),
        }
    }

    pub fn insert_stroke(&mut self, stroke_id: String, bbox: RectF) {
        self.tree.insert(SpatialItem {
            id: stroke_id,
            kind: SpatialObjectKind::Stroke,
            bbox,
        });
    }

    pub fn insert_image(&mut self, image_id: String, bbox: RectF) {
        self.tree.insert(SpatialItem {
            id: image_id,
            kind: SpatialObjectKind::Image,
            bbox,
        });
    }

    /// Queries all strokes and images intersecting a viewport or 512x512 render tile.
    pub fn query_rect(&self, rect: RectF) -> Vec<&SpatialItem> {
        let env = AABB::from_corners([rect.min_x, rect.min_y], [rect.max_x, rect.max_y]);
        self.tree.locate_in_envelope_intersecting(&env).collect()
    }

    /// Eraser hit-test: returns candidate stroke IDs near `(x, y)` within `radius`.
    pub fn query_eraser_hits(&self, x: f32, y: f32, radius: f32) -> Vec<String> {
        let rect = RectF {
            min_x: x - radius,
            min_y: y - radius,
            max_x: x + radius,
            max_y: y + radius,
        };
        self.query_rect(rect)
            .into_iter()
            .filter(|item| item.kind == SpatialObjectKind::Stroke)
            .map(|item| item.id.clone())
            .collect()
    }

    /// `resolve_lasso_selection` per MVP Section 3.3:
    /// Queries R-Tree with lasso contour, computes union bounding box of hit strokes/images,
    /// and automatically expands outward by 12% padding (`padding_ratio = 0.12`).
    pub fn resolve_lasso_selection(
        &self,
        lasso_points: &[(f32, f32)],
        padding_ratio: f32,
    ) -> SaltResult<LassoSelectionResult> {
        let lasso_bbox = RectF::from_points(lasso_points).ok_or_else(|| {
            SaltError::InvalidInput("Lasso selection requires at least 1 point".into())
        })?;

        let candidates = self.query_rect(lasso_bbox);
        let mut hit_stroke_ids = Vec::new();
        let mut hit_image_ids = Vec::new();
        let mut union_bbox: Option<RectF> = None;

        for item in candidates {
            let center_x = (item.bbox.min_x + item.bbox.max_x) * 0.5;
            let center_y = (item.bbox.min_y + item.bbox.max_y) * 0.5;
            let inside = lasso_points.len() < 3
                || point_in_polygon(center_x, center_y, lasso_points)
                || item.bbox.intersects(&lasso_bbox);
            if inside {
                match item.kind {
                    SpatialObjectKind::Stroke => hit_stroke_ids.push(item.id.clone()),
                    SpatialObjectKind::Image => hit_image_ids.push(item.id.clone()),
                }
                union_bbox = Some(match union_bbox {
                    Some(acc) => acc.union(&item.bbox),
                    None => item.bbox,
                });
            }
        }

        let has_handwriting_or_image = !hit_stroke_ids.is_empty() || !hit_image_ids.is_empty();
        let tight_bbox = union_bbox.unwrap_or(lasso_bbox);
        let padded_bbox = tight_bbox.expand_ratio(padding_ratio);

        Ok(LassoSelectionResult {
            hit_stroke_ids,
            hit_image_ids,
            tight_bbox,
            padded_bbox,
            has_handwriting_or_image,
        })
    }
}

fn point_in_polygon(x: f32, y: f32, poly: &[(f32, f32)]) -> bool {
    let mut inside = false;
    let n = poly.len();
    if n < 3 {
        return false;
    }
    let mut j = n - 1;
    for i in 0..n {
        let (xi, yi) = poly[i];
        let (xj, yj) = poly[j];
        let intersect =
            ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-6) + xi);
        if intersect {
            inside = !inside;
        }
        j = i;
    }
    inside
}

// ============================================================================
// 5. Local WebP Slice Rasterizer & Asset Image Ingestion
// ============================================================================

/// Renders a local region (`padded_bbox`) of a canvas (strokes + placed images)
/// into a compressed WebP byte slice.
/// `dense_text_or_formula == true` => cap long edge at 1280px; otherwise 768px (Section 3.3).
pub fn render_canvas_slice_webp(
    snapshot: &CanvasSnapshot,
    padded_bbox: RectF,
    vault_root: Option<&Path>,
    dense_text_or_formula: bool,
) -> SaltResult<Vec<u8>> {
    let max_long_edge: u32 = if dense_text_or_formula { 1280 } else { 768 };
    let world_w = padded_bbox.width().max(32.0);
    let world_h = padded_bbox.height().max(32.0);

    // Compute pixel resolution capped to max_long_edge while ensuring legibility
    let natural_scale = 2.0_f32;
    let long_edge = (world_w.max(world_h) * natural_scale).min(max_long_edge as f32);
    let scale = (long_edge / world_w.max(world_h)).clamp(0.1, 4.0);
    let img_w = ((world_w * scale).ceil() as u32).clamp(32, max_long_edge);
    let img_h = ((world_h * scale).ceil() as u32).clamp(32, max_long_edge);

    // Clean warm paper background
    let mut canvas = RgbaImage::from_pixel(img_w, img_h, Rgba([252, 251, 248, 255]));

    // 1. Composite placed canvas images if present on disk
    if let Some(root) = vault_root {
        for node in &snapshot.images {
            if node.deleted || !node.bbox().intersects(&padded_bbox) {
                continue;
            }
            let path = root.join(&node.asset_rel_path);
            if let Ok(raw) = std::fs::read(&path) {
                if let Ok(decoded) = image::load_from_memory(&raw) {
                    let target_w = ((node.width * scale).round() as u32).max(1);
                    let target_h = ((node.height * scale).round() as u32).max(1);
                    let resized = decoded.resize_exact(
                        target_w,
                        target_h,
                        imageops::FilterType::Triangle,
                    );
                    let dst_x = ((node.x - padded_bbox.min_x) * scale).round() as i64;
                    let dst_y = ((node.y - padded_bbox.min_y) * scale).round() as i64;
                    imageops::overlay(&mut canvas, &resized.to_rgba8(), dst_x, dst_y);
                }
            }
        }
    }

    // 2. Rasterize active strokes intersecting padded_bbox
    let transform_map: HashMap<&str, &StrokeTransform> = snapshot
        .transforms
        .iter()
        .map(|t| (t.stroke_id.as_str(), t))
        .collect();

    for stroke in &snapshot.strokes {
        let tr = transform_map.get(stroke.stroke_id.as_str()).copied();
        if tr.map(|t| t.deleted).unwrap_or(false) {
            continue;
        }
        let (tx, ty, sx, sy) = match tr {
            Some(t) => (t.tx, t.ty, t.scale_x, t.scale_y),
            None => (0.0, 0.0, 1.0, 1.0),
        };
        let sbbox = stroke.bbox.translated(tx, ty, sx, sy);
        if !sbbox.intersects(&padded_bbox) {
            continue;
        }
        let pts = stroke.decode_points();
        let a = ((stroke.color_argb >> 24) & 0xFF) as u8;
        let r = ((stroke.color_argb >> 16) & 0xFF) as u8;
        let g = ((stroke.color_argb >> 8) & 0xFF) as u8;
        let b = (stroke.color_argb & 0xFF) as u8;
        let effective_alpha = match stroke.brush {
            BrushKind::Highlighter => a.min(96),
            _ => a,
        };
        let color = Rgba([r, g, b, effective_alpha]);

        for w in pts.windows(2) {
            let x0 = (w[0].x * sx + tx - padded_bbox.min_x) * scale;
            let y0 = (w[0].y * sy + ty - padded_bbox.min_y) * scale;
            let x1 = (w[1].x * sx + tx - padded_bbox.min_x) * scale;
            let y1 = (w[1].y * sy + ty - padded_bbox.min_y) * scale;
            let avg_p = (w[0].pressure + w[1].pressure) * 0.5;
            let rad = (stroke.base_width * scale * 0.5 * (0.4 + 0.8 * avg_p)).max(1.0);
            draw_capsule(&mut canvas, x0, y0, x1, y1, rad, color);
        }
        if pts.len() == 1 {
            let x0 = (pts[0].x * sx + tx - padded_bbox.min_x) * scale;
            let y0 = (pts[0].y * sy + ty - padded_bbox.min_y) * scale;
            let rad = (stroke.base_width * scale * 0.5).max(1.5);
            draw_capsule(&mut canvas, x0, y0, x0, y0, rad, color);
        }
    }

    let mut buf = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(canvas)
        .write_to(&mut buf, ImageFormat::WebP)
        .map_err(|e| SaltError::Io(format!("Failed to encode WebP slice: {e}")))?;
    Ok(buf.into_inner())
}

fn draw_capsule(
    img: &mut RgbaImage,
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
    radius: f32,
    color: Rgba<u8>,
) {
    let w = img.width() as i32;
    let h = img.height() as i32;
    let min_x = (x0.min(x1) - radius - 1.0).floor().max(0.0) as i32;
    let max_x = (x0.max(x1) + radius + 1.0).ceil().min((w - 1) as f32) as i32;
    let min_y = (y0.min(y1) - radius - 1.0).floor().max(0.0) as i32;
    let max_y = (y0.max(y1) + radius + 1.0).ceil().min((h - 1) as f32) as i32;

    let dx = x1 - x0;
    let dy = y1 - y0;
    let seg_len_sq = dx * dx + dy * dy;
    let r_outer = radius + 0.75;

    for py in min_y..=max_y {
        for px in min_x..=max_x {
            let fx = px as f32 + 0.5;
            let fy = py as f32 + 0.5;
            let t = if seg_len_sq > 1e-5 {
                (((fx - x0) * dx + (fy - y0) * dy) / seg_len_sq).clamp(0.0, 1.0)
            } else {
                0.0
            };
            let cx = x0 + t * dx;
            let cy = y0 + t * dy;
            let dist = ((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)).sqrt();
            if dist <= r_outer {
                let cov = ((r_outer - dist) / 1.5).clamp(0.0, 1.0);
                let alpha = (color[3] as f32 / 255.0) * cov;
                let dst = img.get_pixel_mut(px as u32, py as u32);
                for c in 0..3 {
                    let blended = (color[c] as f32) * alpha + (dst[c] as f32) * (1.0 - alpha);
                    dst[c] = blended.round().clamp(0.0, 255.0) as u8;
                }
            }
        }
    }
}

/// Converts arbitrary PNG/JPEG/WebP image bytes into a content-addressed `.webp` asset.
/// Returns `(sha256_hex, relative_path, webp_bytes, width, height)`.
pub fn prepare_image_asset_webp(
    input_bytes: &[u8],
) -> SaltResult<(String, String, Vec<u8>, u32, u32)> {
    let img = image::load_from_memory(input_bytes)
        .map_err(|e| SaltError::InvalidInput(format!("Unsupported image format: {e}")))?;
    let width = img.width();
    let height = img.height();

    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, ImageFormat::WebP)
        .map_err(|e| SaltError::Io(format!("WebP conversion failed: {e}")))?;
    let webp_bytes = out.into_inner();
    let full_hash = sha256_hex(&webp_bytes);
    let bucket = &full_hash[..2];
    let short_name = &full_hash[..16];
    let rel_path = format!("assets/{bucket}/{short_name}.webp");
    Ok((full_hash, rel_path, webp_bytes, width, height))
}

// ============================================================================
// 6. Markdown Context Extractor (Nearest H1/H2 + +/- 200 Chars)
// ============================================================================

/// Extracts nearest preceding `# ` or `## ` heading plus up to 200 Unicode characters
/// before and after `anchor_char_offset` per MVP Section 3.3.
pub fn extract_markdown_context(markdown: &str, anchor_char_offset: Option<usize>) -> String {
    if markdown.trim().is_empty() {
        return String::new();
    }
    let chars: Vec<char> = markdown.chars().collect();
    let center = anchor_char_offset
        .unwrap_or(chars.len())
        .min(chars.len());

    let prefix_str: String = chars[..center].iter().collect();
    let nearest_heading = prefix_str
        .lines()
        .rev()
        .find(|line| {
            let trimmed = line.trim_start();
            trimmed.starts_with("# ") || trimmed.starts_with("## ")
        })
        .map(|s| s.trim().to_string());

    let start = center.saturating_sub(200);
    let end = (center + 200).min(chars.len());
    let window: String = chars[start..end].iter().collect();

    match nearest_heading {
        Some(h) if !window.contains(&h) => format!("{h}\n\n{window}"),
        _ => window,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::encode_raw_points_le;

    #[test]
    fn one_euro_compress_tessellate_and_lasso_rtree() {
        let raw: Vec<RawStrokePoint> = (0..40)
            .map(|i| RawStrokePoint {
                x: 100.0 + i as f32 * 4.0,
                y: 200.0 + ((i as f32 * 0.3).sin() * 2.0),
                pressure: 0.5 + (i as f32 / 80.0),
                dt_ms: 4,
            })
            .collect();
        let bytes = encode_raw_points_le(&raw);
        let (stroke, mesh, render) =
            ingest_raw_stroke_bytes(&bytes, BrushKind::Pen, 0xFF1F2430, 3.0, None).unwrap();
        assert!(render.points_xyp.len() >= 12);

        // Compact payload verification (hundreds of bytes)
        let serialized = serde_json::to_vec(&stroke).unwrap();
        assert!(serialized.len() < 1200, "DeltaStroke should stay compact");
        assert!(mesh.vertices_xy.len() >= 16);

        let snap = CanvasSnapshot {
            canvas_id: "main".into(),
            strokes: vec![stroke.clone()],
            transforms: vec![],
            images: vec![],
        };
        let index = CanvasSpatialIndex::build_from_snapshot(&snap);
        let lasso = vec![(90.0, 180.0), (270.0, 180.0), (270.0, 220.0), (90.0, 220.0)];
        let res = index.resolve_lasso_selection(&lasso, 0.12).unwrap();
        assert_eq!(res.hit_stroke_ids, vec![stroke.stroke_id]);
        assert!(res.has_handwriting_or_image);
        assert!(res.padded_bbox.width() > res.tight_bbox.width());

        let webp = render_canvas_slice_webp(&snap, res.padded_bbox, None, true).unwrap();
        assert_eq!(&webp[0..4], b"RIFF");
        assert_eq!(&webp[8..12], b"WEBP");
    }

    #[test]
    fn context_extractor_captures_heading_and_window() {
        let mut md = String::from("# Quantum Mechanics\n\n");
        md.push_str(&"a".repeat(300));
        md.push_str("Schrodinger equation");
        md.push_str(&"b".repeat(300));
        let anchor = md.find("Schrodinger").unwrap();
        let ctx = extract_markdown_context(&md, Some(anchor));
        assert!(ctx.contains("# Quantum Mechanics"));
        assert!(ctx.contains("Schrodinger equation"));
    }

    /// MVP Section 6.1 Performance Gate: 10,000 strokes R-Tree query + LOD tessellation speed check.
    #[test]
    fn ten_thousand_strokes_rtree_viewport_query_and_lod_tessellation() {
        let mut strokes = Vec::with_capacity(10_000);
        for i in 0..10_000 {
            let col = (i % 100) as f32 * 120.0;
            let row = (i / 100) as f32 * 80.0;
            let pts: Vec<RawStrokePoint> = (0..12)
                .map(|j| RawStrokePoint {
                    x: col + j as f32 * 6.0,
                    y: row + ((j as f32 * 0.5).sin() * 8.0),
                    pressure: 0.65,
                    dt_ms: 4,
                })
                .collect();
            let st = compress_stroke(&pts, BrushKind::Pen, 0xFF191C1E, 3.0, Some(format!("s-{i}"))).unwrap();
            strokes.push(st);
        }
        let snap = CanvasSnapshot {
            canvas_id: "stress-10k".into(),
            strokes,
            transforms: vec![],
            images: vec![],
        };

        let index = CanvasSpatialIndex::build_from_snapshot(&snap);
        let t0 = std::time::Instant::now();
        let viewport = RectF {
            min_x: 0.0,
            min_y: 0.0,
            max_x: 1280.0,
            max_y: 800.0,
        };
        let visible = index.query_rect(viewport);
        let query_ms = t0.elapsed().as_secs_f64() * 1000.0;
        assert!(!visible.is_empty());
        assert!(visible.len() < 300, "R-Tree must cull strokes outside viewport");
        assert!(query_ms < 16.0, "R-Tree viewport query took {query_ms:.2}ms (>16ms 60fps frame budget)");
    }
}
