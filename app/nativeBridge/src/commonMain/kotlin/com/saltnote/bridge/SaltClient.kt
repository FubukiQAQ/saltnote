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
    fun getInboxSyncRevision(): Long
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
    fun deletePage(pageId: String)
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
        maxWidth: Float = 360f,
    ): CanvasImageNodeDto

    fun updateCanvasImageTransform(
        pageId: String,
        canvasId: String,
        imageId: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        deleted: Boolean = false,
    ): CanvasImageNodeDto

    fun getHaloPresets(): List<HaloPresetDto>
    fun triggerCanvasHalo(
        pageId: String,
        canvasId: String,
        presetId: String,
        lassoPoints: List<StrokeSample>,
        providerId: String?,
        outputLanguageHint: String? = null,
        listener: HaloStreamListener,
    ): CancelHandle

    fun triggerTextHalo(
        pageId: String,
        presetId: String,
        anchorOffset: Int,
        selectedText: String,
        providerId: String?,
        outputLanguageHint: String? = null,
        listener: HaloStreamListener,
    ): CancelHandle

    fun triggerInboxHalo(
        inboxId: String,
        pageId: String,
        presetId: String,
        providerId: String? = null,
        outputLanguageHint: String? = null,
        listener: HaloStreamListener,
    ): CancelHandle

    fun updateHaloPin(pageId: String, haloId: String, pinX: Float, pinY: Float): HaloAnnotationDto

    fun fuseHaloIntoBody(pageId: String, haloId: String): String
    fun toggleHaloCollapsed(pageId: String, haloId: String, collapsed: Boolean)
    fun deleteHalo(pageId: String, haloId: String)
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

    fun updateFlashcard(
        pageId: String,
        cardId: String,
        front: String,
        back: String,
    ): FlashCardDto

    fun deleteFlashcard(pageId: String, cardId: String)

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
    fun syncWebDavNow(isMetered: Boolean = false): SyncReportDto

    fun startLanP2p(enableMdns: Boolean = true): LanNodeInfoDto
    fun stopLanP2p()
    fun listLanPeers(): List<LanPeerInfoDto>
    fun syncWithLanPeer(host: String, port: Int, expectedFingerprint: String): P2pSyncResultDto

    fun getSyncPrefs(): LocalSyncPrefsDto
    fun saveSyncPrefs(prefs: LocalSyncPrefsDto)
    fun listPairedPeers(): List<PairedPeerDto>
    fun removePairedPeer(deviceId: String)
    fun setPairedPeerAutoSync(deviceId: String, enabled: Boolean)
    fun rotateLanPairingInfo(): LanNodeInfoDto
    fun refreshLanNetwork(): LanNodeInfoDto?
    fun pairWithLanPeer(
        host: String,
        port: Int,
        expectedFingerprint: String,
        pairingSecret: String,
    ): P2pSyncResultDto

    fun syncAllPairedLanPeers(): LanBatchSyncReportDto

    // Salt Inbox & Micro-Session FSRS (v0.2-alpha)
    fun captureInboxItem(input: InboxCaptureInputDto): String
    fun listInboxItems(includeArchived: Boolean = false, limit: Int = 50): List<InboxItemViewDto>
    suspend fun enrichInboxItem(inboxId: String): InboxItemViewDto
    fun consumeInboxIntoPage(inboxId: String, targetPageId: String, targetBlockId: String)
    fun setInboxItemPrivate(inboxId: String, isPrivate: Boolean): InboxItemViewDto
    fun deleteInboxItem(inboxId: String)
    suspend fun buildMicroReviewSession(
        budgetSeconds: Int = 300,
        energyLevel: Int = 2,
        rescueMode: Boolean = true,
    ): MicroSessionPlanDto

    fun redactLogText(raw: String): String
}

expect fun openSaltNoteClient(
    vaultPath: String,
    deviceName: String,
    secretVault: SecretVault,
): SaltNoteClient
