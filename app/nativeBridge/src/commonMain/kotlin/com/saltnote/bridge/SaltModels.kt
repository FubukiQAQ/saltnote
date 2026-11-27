package com.saltnote.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PageMetaDto(
    @SerialName("page_id") val pageId: String,
    val title: String,
    val mode: String = "study", // "journal" | "study"
    @SerialName("is_private") val isPrivate: Boolean = false,
    val tags: List<String> = emptyList(),
    @SerialName("created_at_utc") val createdAtUtc: Long = 0L,
    @SerialName("updated_at_utc") val updatedAtUtc: Long = 0L,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
)

@Serializable
data class PageCanvasSummaryDto(
    @SerialName("canvas_id") val canvasId: String,
    val title: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0L,
    @SerialName("stroke_count") val strokeCount: Int = 0,
    @SerialName("image_count") val imageCount: Int = 0,
)

@Serializable
data class PageDocumentSummaryDto(
    @SerialName("doc_id") val docId: String,
    val title: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0L,
    @SerialName("updated_at_utc") val updatedAtUtc: Long = 0L,
)

@Serializable
data class PageDocumentSnapshotDto(
    @SerialName("doc_id") val docId: String,
    val title: String = "",
    @SerialName("body_markdown") val bodyMarkdown: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0L,
    @SerialName("created_at_utc") val createdAtUtc: Long = 0L,
    @SerialName("updated_at_utc") val updatedAtUtc: Long = 0L,
    val deleted: Boolean = false,
)

@Serializable
data class PageSummaryDto(
    val meta: PageMetaDto,
    val snippet: String = "",
    @SerialName("stroke_count") val strokeCount: Int = 0,
    @SerialName("card_count") val cardCount: Int = 0,
    @SerialName("markdown_file") val markdownFile: String = "",
    val canvases: List<PageCanvasSummaryDto> = emptyList(),
    val documents: List<PageDocumentSummaryDto> = emptyList(),
)

val DEFAULT_PEN_PALETTE_ARGB: List<Long> = listOf(
    0xFF191C1EL,
    0xFF1A657AL,
    0xFFC62828L,
    0xFF2E7D32L,
    0xFF6A1B9AL,
)

@Serializable
data class RecentPageVisitDto(
    @SerialName("page_id") val pageId: String,
    @SerialName("visited_at_utc") val visitedAtUtc: Long = 0L,
    @SerialName("last_mode") val lastMode: String? = null,
    @SerialName("last_canvas_id") val lastCanvasId: String? = null,
    @SerialName("last_doc_id") val lastDocId: String? = null,
)

@Serializable
data class RecentCardVisitDto(
    @SerialName("card_id") val cardId: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("visited_at_utc") val visitedAtUtc: Long = 0L,
)

@Serializable
data class LocalUiPrefsDto(
    @SerialName("pen_palette_argb") val penPaletteArgb: List<Long> = DEFAULT_PEN_PALETTE_ARGB,
    @SerialName("recent_custom_colors_argb") val recentCustomColorsArgb: List<Long> = emptyList(),
    @SerialName("recent_pages") val recentPages: List<RecentPageVisitDto> = emptyList(),
    @SerialName("recent_cards") val recentCards: List<RecentCardVisitDto> = emptyList(),
)

@Serializable
data class RectFDto(
    @SerialName("min_x") val minX: Float,
    @SerialName("min_y") val minY: Float,
    @SerialName("max_x") val maxX: Float,
    @SerialName("max_y") val maxY: Float,
)

@Serializable
data class DeltaStrokeDto(
    @SerialName("stroke_id") val strokeId: String,
    val brush: String,
    @SerialName("color_argb") val colorArgb: Long,
    @SerialName("base_width") val baseWidth: Float,
    @SerialName("origin_x") val originX: Float,
    @SerialName("origin_y") val originY: Float,
    @SerialName("quant_scale") val quantScale: Float,
    val dx: List<Int>,
    val dy: List<Int>,
    @SerialName("pressure_q") val pressureQ: List<Int>,
    @SerialName("dt_ms") val dtMs: List<Int>,
    val bbox: RectFDto,
    @SerialName("created_at_utc") val createdAtUtc: Long,
)

@Serializable
data class StrokeTransformDto(
    @SerialName("stroke_id") val strokeId: String,
    val tx: Float = 0f,
    val ty: Float = 0f,
    @SerialName("scale_x") val scaleX: Float = 1f,
    @SerialName("scale_y") val scaleY: Float = 1f,
    val deleted: Boolean = false,
    @SerialName("updated_at_utc") val updatedAtUtc: Long = 0L,
)

@Serializable
data class CanvasImageNodeDto(
    @SerialName("image_id") val imageId: String,
    @SerialName("asset_hash") val assetHash: String,
    @SerialName("asset_rel_path") val assetRelPath: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val deleted: Boolean = false,
    @SerialName("updated_at_utc") val updatedAtUtc: Long = 0L,
)

@Serializable
data class CanvasSnapshotDto(
    @SerialName("canvas_id") val canvasId: String,
    val title: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0L,
    @SerialName("created_at_utc") val createdAtUtc: Long = 0L,
    @SerialName("is_inline") val isInline: Boolean = false,
    val deleted: Boolean = false,
    val strokes: List<DeltaStrokeDto> = emptyList(),
    val transforms: List<StrokeTransformDto> = emptyList(),
    val images: List<CanvasImageNodeDto> = emptyList(),
)

@Serializable
data class TessellatedMeshDto(
    @SerialName("stroke_id") val strokeId: String,
    val brush: String,
    @SerialName("color_argb") val colorArgb: Long,
    @SerialName("vertices_xy") val verticesXy: List<Float>,
    val bbox: RectFDto,
)

@Serializable
data class RenderStrokeDto(
    @SerialName("stroke_id") val strokeId: String,
    val brush: String,
    @SerialName("color_argb") val colorArgb: Long,
    @SerialName("base_width") val baseWidth: Float,
    @SerialName("points_xyp") val pointsXyp: List<Float>,
    val bbox: RectFDto,
)

@Serializable
data class StrokeIngestResponseDto(
    @SerialName("stroke_id") val strokeId: String,
    val mesh: TessellatedMeshDto,
    @SerialName("render_stroke") val renderStroke: RenderStrokeDto,
)

@Serializable
data class HaloPresetDto(
    @SerialName("preset_id") val presetId: String,
    val name: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("color_argb") val colorArgb: Long,
    @SerialName("vision_policy") val visionPolicy: String,
    @SerialName("output_mode") val outputMode: String,
    @SerialName("system_prompt") val systemPrompt: String,
)

@Serializable
data class HaloAnnotationDto(
    @SerialName("halo_id") val haloId: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("canvas_id") val canvasId: String? = null,
    @SerialName("doc_id") val docId: String? = null,
    @SerialName("preset_id") val presetId: String,
    @SerialName("anchor_bbox") val anchorBbox: RectFDto? = null,
    @SerialName("anchor_text_offset") val anchorTextOffset: Int? = null,
    @SerialName("pin_x") val pinX: Float? = null,
    @SerialName("pin_y") val pinY: Float? = null,
    @SerialName("slice_cache_path") val sliceCachePath: String? = null,
    @SerialName("slice_asset_rel_path") val sliceAssetRelPath: String? = null,
    @SerialName("context_excerpt") val contextExcerpt: String = "",
    @SerialName("response_markdown") val responseMarkdown: String = "",
    val collapsed: Boolean = false,
    val fused: Boolean = false,
    @SerialName("created_at_utc") val createdAtUtc: Long = 0L,
)

@Serializable
data class FlashCardDto(
    @SerialName("card_id") val cardId: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("anchor_halo_id") val anchorHaloId: String? = null,
    val front: String,
    val back: String,
    @SerialName("created_at_utc") val createdAtUtc: Long = 0L,
    val archived: Boolean = false,
)

@Serializable
data class FlashcardDraftDto(
    val front: String,
    val back: String,
)

@Serializable
data class FsrsCardStateDto(
    @SerialName("card_id") val cardId: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("anchor_halo_id") val anchorHaloId: String? = null,
    val front: String,
    val back: String,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: Int = 0,
    @SerialName("last_review_utc") val lastReviewUtc: Long? = null,
    @SerialName("due_at_utc") val dueAtUtc: Long = 0L,
)

@Serializable
data class PageSnapshotDto(
    val meta: PageMetaDto,
    @SerialName("body_markdown") val bodyMarkdown: String = "",
    val canvases: List<CanvasSnapshotDto> = emptyList(),
    val documents: List<PageDocumentSnapshotDto> = emptyList(),
    val halos: List<HaloAnnotationDto> = emptyList(),
    val cards: List<FlashCardDto> = emptyList(),
)

@Serializable
data class ProviderConfigDto(
    val id: String,
    val name: String,
    val kind: String, // "open_ai_compatible" | "anthropic" | "gemini" | "ollama_local"
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    @SerialName("api_key_ref") val apiKeyRef: String,
)

@Serializable
data class RouterConfigDto(
    @SerialName("default_text_provider") val defaultTextProvider: String,
    @SerialName("default_vision_provider") val defaultVisionProvider: String,
    @SerialName("default_audio_provider") val defaultAudioProvider: String = "gemini-byok",
    @SerialName("local_whisper_url") val localWhisperUrl: String = "",
    @SerialName("salt_relay_enabled") val saltRelayEnabled: Boolean = false,
    @SerialName("ui_language") val uiLanguage: String = "system",
    @SerialName("ai_output_language") val aiOutputLanguage: String = "follow_ui",
    val providers: List<ProviderConfigDto>,
)

@Serializable
data class ConnectivityTestResultDto(
    val ok: Boolean,
    @SerialName("latency_ms") val latencyMs: Long,
    val message: String,
)

@Serializable
data class SearchHitDto(
    @SerialName("page_id") val pageId: String,
    val title: String,
    val snippet: String,
    val score: Double = 0.0,
    @SerialName("is_private") val isPrivate: Boolean = false,
)

@Serializable
data class WebDavConfigDto(
    val endpoint: String = "",
    @SerialName("root_path") val rootPath: String = "/SaltNoteVault",
    val username: String = "",
    @SerialName("password_key_ref") val passwordKeyRef: String = "vault://webdav_password",
    val enabled: Boolean = false,
)

@Serializable
data class SyncReportDto(
    @SerialName("uploaded_deltas") val uploadedDeltas: Int = 0,
    @SerialName("downloaded_deltas") val downloadedDeltas: Int = 0,
    @SerialName("uploaded_assets") val uploadedAssets: Int = 0,
    @SerialName("downloaded_assets") val downloadedAssets: Int = 0,
    @SerialName("deferred_assets") val deferredAssets: Int = 0,
    @SerialName("retried_429_count") val retried429Count: Int = 0,
    val errors: List<String> = emptyList(),
)

@Serializable
data class DeviceStateDto(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("last_seq") val lastSeq: Long = 0L,
)

@Serializable
data class LanNodeInfoDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("host_ip") val hostIp: String = "",
    val port: Int,
    val fingerprint: String,
    @SerialName("pairing_token") val pairingToken: String = "",
    @SerialName("pairing_pin") val pairingPin: String = "",
    @SerialName("pairing_expires_at_utc") val pairingExpiresAtUtc: Long = 0L,
)

@Serializable
data class LanPeerInfoDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val host: String,
    val port: Int,
    @SerialName("cert_fingerprint") val certFingerprint: String,
    @SerialName("relay_available") val relayAvailable: Boolean = false,
    @SerialName("is_paired") val isPaired: Boolean = false,
)

@Serializable
data class PairedPeerDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("cert_fingerprint") val certFingerprint: String,
    @SerialName("last_host") val lastHost: String = "",
    @SerialName("last_port") val lastPort: Int = 47821,
    @SerialName("paired_at_utc") val pairedAtUtc: Long = 0L,
    @SerialName("last_sync_utc") val lastSyncUtc: Long = 0L,
    @SerialName("auto_sync") val autoSync: Boolean = true,
)

@Serializable
data class LocalSyncPrefsDto(
    @SerialName("lan_auto_start") val lanAutoStart: Boolean = true,
    @SerialName("auto_sync_on_capture") val autoSyncOnCapture: Boolean = true,
    @SerialName("auto_sync_on_edit") val autoSyncOnEdit: Boolean = true,
    @SerialName("auto_sync_webdav") val autoSyncWebdav: Boolean = true,
    @SerialName("mirror_webdav_after_lan") val mirrorWebdavAfterLan: Boolean = false,
)

@Serializable
data class LanBatchSyncReportDto(
    @SerialName("attempted_peers") val attemptedPeers: Int = 0,
    @SerialName("succeeded_peers") val succeededPeers: Int = 0,
    @SerialName("total_sent_packets") val totalSentPackets: Int = 0,
    @SerialName("total_received_packets") val totalReceivedPackets: Int = 0,
    @SerialName("total_sent_assets") val totalSentAssets: Int = 0,
    @SerialName("total_received_assets") val totalReceivedAssets: Int = 0,
    @SerialName("synced_device_ids") val syncedDeviceIds: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

enum class InboxKindDto(val wireName: String) {
    QuickText("quick_text"),
    PhotoNote("photo_note"),
    VoiceMemo("voice_memo"),
    WebClip("web_clip"),
}

data class InboxCaptureInputDto(
    val kind: InboxKindDto,
    val textNote: String? = null,
    val primaryMediaBytes: ByteArray? = null,
    val primaryMediaExt: String? = null,
    val thumbWebpBytes: ByteArray? = null,
    val audioDurationMs: Int = 0,
    val isPrivate: Boolean = false,
)

data class InboxItemViewDto(
    val inboxId: String,
    val kind: InboxKindDto,
    val createdAtUtc: Long,
    val isPrivate: Boolean,
    val state: String,
    val textNote: String? = null,
    val transcriptText: String? = null,
    val summaryText: String? = null,
    val autoTags: List<String> = emptyList(),
    val transcriptStatus: String = "not_applicable",
    val thumbAssetPath: String? = null,
    val primaryAssetPath: String? = null,
    val audioDurationMs: Int = 0,
    val consumedPageId: String? = null,
    val consumedBlockId: String? = null,
)

data class ReviewCardViewDto(
    val cardId: String,
    val pageId: String,
    val anchorHaloId: String? = null,
    val front: String,
    val back: String,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: Int = 0,
    val lastReviewUtc: Long? = null,
    val dueAtUtc: Long = 0L,
    val retrievability: Double = 0.0,
    val sourcePageTitle: String = "",
    val sliceAssetPath: String? = null,
    val contextExcerpt: String? = null,
)

data class MicroSessionPlanDto(
    val sessionId: String,
    val budgetSeconds: Int,
    val estimatedSeconds: Int,
    val cards: List<ReviewCardViewDto>,
    val totalDueCount: Int,
)

@Serializable
data class P2pSyncResultDto(
    @SerialName("peer_device_id") val peerDeviceId: String,
    @SerialName("sent_packets") val sentPackets: Int,
    @SerialName("received_packets") val receivedPackets: Int,
    @SerialName("sent_assets") val sentAssets: Int = 0,
    @SerialName("received_assets") val receivedAssets: Int = 0,
)

data class StrokeSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val dtMs: Int,
)

/**
 * Packs raw 240Hz stylus points into the exact 16-byte little-endian wire layout expected by `salt-canvas`:
 * `[f32 x, f32 y, f32 pressure, u32 dt_ms]`.
 */
fun encodeStrokePointsLe(points: List<StrokeSample>): ByteArray {
    val out = ByteArray(points.size * 16)
    var offset = 0
    for (p in points) {
        writeInt32Le(out, offset, p.x.toRawBits())
        writeInt32Le(out, offset + 4, p.y.toRawBits())
        writeInt32Le(out, offset + 8, p.pressure.coerceIn(0f, 1f).toRawBits())
        writeInt32Le(out, offset + 12, p.dtMs.coerceAtLeast(0))
        offset += 16
    }
    return out
}

private fun writeInt32Le(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = (value and 0xFF).toByte()
    dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}
