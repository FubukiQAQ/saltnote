package com.saltnote.bridge

/**
 * OS-backed secure secret store (Android Keystore / Windows DPAPI / macOS Keychain / Linux SecretService).
 */
interface SecretVault {
    fun getSecret(keyRef: String): String?
    fun putSecret(keyRef: String, secretValue: String)
    fun deleteSecret(keyRef: String)
    fun hasSecret(keyRef: String): Boolean = !getSecret(keyRef).isNullOrEmpty()
}

interface HaloStreamListener {
    fun onDelta(chunk: String)
    fun onComplete(fullMarkdown: String, halo: HaloAnnotationDto?)
    fun onError(code: String, message: String)
}

interface CancelHandle {
    fun cancel()
    fun isCancelled(): Boolean
}

interface SaltNoteClient {
    val vaultPath: String
    val secretVault: SecretVault

    fun getSyncRevision(): Long
    fun getDeviceState(): DeviceStateDto
    fun listPages(): List<PageSummaryDto>
    fun ensureWelcomePage(): PageMetaDto
    fun createPage(
        title: String,
        mode: String = "study",
        isPrivate: Boolean = false,
        tags: List<String> = emptyList(),
        initialMarkdown: String = "",
    ): PageMetaDto

    fun getPageSnapshot(pageId: String): PageSnapshotDto
    fun updatePageMeta(
        pageId: String,
        title: String? = null,
        mode: String? = null,
        isPrivate: Boolean? = null,
        tags: List<String>? = null,
    ): PageMetaDto

    fun updatePageBody(pageId: String, markdown: String)
    fun flushDeltas(): Int
    fun exportMarkdownBackup(targetDir: String): Int

    fun ingestRawStroke(
        pageId: String,
        canvasId: String,
        brushKind: String,
        colorArgb: Long,
        baseWidth: Float,
        points: List<StrokeSample>,
    ): StrokeIngestResponseDto

    fun getCanvasStrokes(
        pageId: String,
        canvasId: String,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): List<RenderStrokeDto>

    fun getCanvasMeshes(
        pageId: String,
        canvasId: String,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        lodZoom: Float,
    ): List<TessellatedMeshDto>

    fun eraseStrokesAt(
        pageId: String,
        canvasId: String,
        x: Float,
        y: Float,
        radius: Float,
    ): List<String>

    fun insertCanvasImage(
        pageId: String,
        canvasId: String,
        rawImageBytes: ByteArray,
        x: Float,
        y: Float,
        maxWidth: Float = 420f,
    ): CanvasImageNodeDto

    fun getHaloPresets(): List<HaloPresetDto>
    fun triggerCanvasHalo(
        pageId: String,
        canvasId: String,
        presetId: String,
        lassoPoints: List<StrokeSample>,
        providerId: String?,
        listener: HaloStreamListener,
    ): CancelHandle

    fun triggerTextHalo(
        pageId: String,
        presetId: String,
        anchorOffset: Int,
        selectedText: String,
        providerId: String?,
        listener: HaloStreamListener,
    ): CancelHandle

    fun fuseHaloIntoBody(pageId: String, haloId: String): String
    fun toggleHaloCollapsed(pageId: String, haloId: String, collapsed: Boolean)
    fun parseFlashcardDrafts(rawOutput: String): List<FlashcardDraftDto>

    fun getRouterConfig(): RouterConfigDto
    fun saveRouterConfig(config: RouterConfigDto)
    fun testProviderConnection(providerId: String): ConnectivityTestResultDto

    fun searchNotes(query: String, limit: Int = 30): List<SearchHitDto>
    fun createFlashcard(
        pageId: String,
        anchorHaloId: String?,
        front: String,
        back: String,
    ): FlashCardDto

    fun listDueCards(): List<FsrsCardStateDto>
    fun listAllCards(): List<FsrsCardStateDto>
    fun submitCardReview(
        pageId: String,
        cardId: String,
        rating: Int,
        durationMs: Int,
    ): FsrsCardStateDto

    fun getWebDavConfig(): WebDavConfigDto
    fun saveWebDavConfig(config: WebDavConfigDto)
    fun syncWebDavNow(): SyncReportDto

    fun startLanP2p(enableMdns: Boolean = true): LanNodeInfoDto
    fun stopLanP2p()
    fun listLanPeers(): List<LanPeerInfoDto>
    fun syncWithLanPeer(host: String, port: Int, expectedFingerprint: String): P2pSyncResultDto

    fun redactLogText(raw: String): String
}

expect fun openSaltNoteClient(
    vaultPath: String,
    deviceName: String,
    secretVault: SecretVault,
): SaltNoteClient
