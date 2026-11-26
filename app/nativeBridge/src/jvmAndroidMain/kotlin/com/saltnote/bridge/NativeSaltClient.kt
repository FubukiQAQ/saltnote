package com.saltnote.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.saltnote_ffi.FfiException
import uniffi.saltnote_ffi.HaloCancelHandle
import uniffi.saltnote_ffi.HaloStreamCallback
import uniffi.saltnote_ffi.InboxCaptureInput
import uniffi.saltnote_ffi.InboxItemView
import uniffi.saltnote_ffi.InboxKind
import uniffi.saltnote_ffi.InboxService
import uniffi.saltnote_ffi.IntelService
import uniffi.saltnote_ffi.PlatformSecretStore
import uniffi.saltnote_ffi.ReviewCardView
import uniffi.saltnote_ffi.SaltEngine

private val saltJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

actual fun openSaltNoteClient(
    vaultPath: String,
    deviceName: String,
    secretVault: SecretVault,
): SaltNoteClient = NativeSaltNoteClient(vaultPath, deviceName, secretVault)

private class NativeSaltNoteClient(
    override val vaultPath: String,
    deviceName: String,
    override val secretVault: SecretVault,
) : SaltNoteClient {

    private val engine: SaltEngine = SaltEngine.open(
        vaultPath = vaultPath,
        deviceName = deviceName,
        secretStore = object : PlatformSecretStore {
            override fun getSecret(keyRef: String): String {
                return secretVault.getSecret(keyRef)
                    ?: throw FfiException.NotFound("Secret not configured for handle: $keyRef")
            }
        },
    )
    private val inboxService: InboxService = engine.inbox()
    private val intelService: IntelService = engine.intel()

    override fun getSyncRevision(): Long = engine.getSyncRevision().toLong()

    override fun getInboxSyncRevision(): Long = engine.getInboxSyncRevision().toLong()

    override fun getDeviceState(): DeviceStateDto =
        saltJson.decodeFromString(engine.getDeviceStateJson())

    override fun listPages(): List<PageSummaryDto> =
        saltJson.decodeFromString(engine.listPagesJson())

    override fun ensureWelcomePage(): PageMetaDto =
        saltJson.decodeFromString(engine.ensureWelcomePageJson())

    override fun createPage(
        title: String,
        mode: String,
        isPrivate: Boolean,
        tags: List<String>,
        initialMarkdown: String,
    ): PageMetaDto = saltJson.decodeFromString(
        engine.createPage(
            title = title,
            mode = mode,
            isPrivate = isPrivate,
            tagsJson = saltJson.encodeToString(tags),
            initialMarkdown = initialMarkdown,
        ),
    )

    override fun getPageSnapshot(pageId: String): PageSnapshotDto =
        saltJson.decodeFromString(engine.getPageSnapshotJson(pageId))

    override fun updatePageMeta(
        pageId: String,
        title: String?,
        mode: String?,
        isPrivate: Boolean?,
        tags: List<String>?,
    ): PageMetaDto = saltJson.decodeFromString(
        engine.updatePageMeta(
            pageId = pageId,
            title = title,
            mode = mode,
            isPrivate = isPrivate,
            tagsJson = tags?.let { saltJson.encodeToString(it) },
        ),
    )

    override fun updatePageBody(pageId: String, markdown: String) {
        engine.updatePageBody(pageId, markdown)
    }

    override fun deletePage(pageId: String) {
        engine.deletePage(pageId)
    }

    override fun flushDeltas(): Int = engine.flushDeltas().toInt()

    override fun exportMarkdownBackup(targetDir: String): Int =
        engine.exportMarkdownBackup(targetDir).toInt()

    override fun ingestRawStroke(
        pageId: String,
        canvasId: String,
        brushKind: String,
        colorArgb: Long,
        baseWidth: Float,
        points: List<StrokeSample>,
    ): StrokeIngestResponseDto {
        val raw = encodeStrokePointsLe(points)
        val json = engine.ingestRawStroke(
            pageId = pageId,
            canvasId = canvasId,
            brushKind = brushKind,
            colorArgb = colorArgb.toUInt(),
            baseWidth = baseWidth,
            rawPointsLe = raw,
        )
        return saltJson.decodeFromString(json)
    }

    override fun getCanvasStrokes(
        pageId: String,
        canvasId: String,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): List<RenderStrokeDto> = saltJson.decodeFromString(
        engine.getCanvasStrokes(pageId, canvasId, minX, minY, maxX, maxY),
    )

    override fun getCanvasMeshes(
        pageId: String,
        canvasId: String,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        lodZoom: Float,
    ): List<TessellatedMeshDto> = saltJson.decodeFromString(
        engine.getCanvasMeshes(pageId, canvasId, minX, minY, maxX, maxY, lodZoom),
    )

    override fun eraseStrokesAt(
        pageId: String,
        canvasId: String,
        x: Float,
        y: Float,
        radius: Float,
    ): List<String> = engine.eraseStrokesAt(pageId, canvasId, x, y, radius)

    override fun insertCanvasImage(
        pageId: String,
        canvasId: String,
        rawImageBytes: ByteArray,
        x: Float,
        y: Float,
        maxWidth: Float,
    ): CanvasImageNodeDto = saltJson.decodeFromString(
        engine.insertCanvasImage(pageId, canvasId, rawImageBytes, x, y, maxWidth),
    )

    override fun updateCanvasImageTransform(
        pageId: String,
        canvasId: String,
        imageId: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        deleted: Boolean,
    ): CanvasImageNodeDto = saltJson.decodeFromString(
        engine.updateCanvasImageTransform(pageId, canvasId, imageId, x, y, width, height, deleted),
    )

    override fun getHaloPresets(): List<HaloPresetDto> =
        saltJson.decodeFromString(engine.getHaloPresetsJson())

    override fun triggerCanvasHalo(
        pageId: String,
        canvasId: String,
        presetId: String,
        lassoPoints: List<StrokeSample>,
        providerId: String?,
        outputLanguageHint: String?,
        listener: HaloStreamListener,
    ): CancelHandle {
        val raw = encodeStrokePointsLe(lassoPoints)
        val handle = engine.triggerCanvasHalo(
            pageId = pageId,
            canvasId = canvasId,
            presetId = presetId,
            lassoPointsLe = raw,
            providerId = providerId,
            outputLanguageHint = outputLanguageHint,
            callback = wrapCallback(listener),
        )
        return NativeCancelHandle(handle)
    }

    override fun triggerTextHalo(
        pageId: String,
        presetId: String,
        anchorOffset: Int,
        selectedText: String,
        providerId: String?,
        outputLanguageHint: String?,
        listener: HaloStreamListener,
    ): CancelHandle {
        val handle = engine.triggerTextHalo(
            pageId = pageId,
            presetId = presetId,
            anchorOffset = anchorOffset.coerceAtLeast(0).toUInt(),
            selectedText = selectedText,
            providerId = providerId,
            outputLanguageHint = outputLanguageHint,
            callback = wrapCallback(listener),
        )
        return NativeCancelHandle(handle)
    }

    override fun triggerInboxHalo(
        inboxId: String,
        pageId: String,
        presetId: String,
        providerId: String?,
        outputLanguageHint: String?,
        listener: HaloStreamListener,
    ): CancelHandle {
        val handle = engine.triggerInboxHalo(
            inboxId = inboxId,
            pageId = pageId,
            presetId = presetId,
            providerId = providerId,
            outputLanguageHint = outputLanguageHint,
            callback = wrapCallback(listener),
        )
        return NativeCancelHandle(handle)
    }

    override fun updateHaloPin(pageId: String, haloId: String, pinX: Float, pinY: Float): HaloAnnotationDto =
        saltJson.decodeFromString(engine.updateHaloPin(pageId, haloId, pinX, pinY))

    override fun fuseHaloIntoBody(pageId: String, haloId: String): String =
        engine.fuseHaloIntoBody(pageId, haloId)

    override fun toggleHaloCollapsed(pageId: String, haloId: String, collapsed: Boolean) {
        engine.toggleHaloCollapsed(pageId, haloId, collapsed)
    }

    override fun deleteHalo(pageId: String, haloId: String) {
        engine.deleteHalo(pageId, haloId)
    }

    override fun parseFlashcardDrafts(rawOutput: String): List<FlashcardDraftDto> =
        saltJson.decodeFromString(engine.parseFlashcardDraftsJson(rawOutput))

    override fun getRouterConfig(): RouterConfigDto =
        saltJson.decodeFromString(engine.getRouterConfigJson())

    override fun saveRouterConfig(config: RouterConfigDto) {
        engine.saveRouterConfigJson(saltJson.encodeToString(config))
    }

    override fun testProviderConnection(providerId: String): ConnectivityTestResultDto =
        saltJson.decodeFromString(engine.testProviderConnectionJson(providerId))

    override fun searchNotes(query: String, limit: Int): List<SearchHitDto> =
        saltJson.decodeFromString(engine.searchNotesJson(query, limit.toUInt()))

    override fun createFlashcard(
        pageId: String,
        anchorHaloId: String?,
        front: String,
        back: String,
    ): FlashCardDto = saltJson.decodeFromString(
        engine.createFlashcard(pageId, anchorHaloId, front, back),
    )

    override fun updateFlashcard(
        pageId: String,
        cardId: String,
        front: String,
        back: String,
    ): FlashCardDto = saltJson.decodeFromString(
        engine.updateFlashcard(pageId, cardId, front, back),
    )

    override fun deleteFlashcard(pageId: String, cardId: String) {
        engine.deleteFlashcard(pageId, cardId)
    }

    override fun listDueCards(): List<FsrsCardStateDto> =
        saltJson.decodeFromString(engine.listDueCardsJson())

    override fun listAllCards(): List<FsrsCardStateDto> =
        saltJson.decodeFromString(engine.listAllCardsJson())

    override fun submitCardReview(
        pageId: String,
        cardId: String,
        rating: Int,
        durationMs: Int,
    ): FsrsCardStateDto = saltJson.decodeFromString(
        engine.submitCardReview(pageId, cardId, rating.toUByte(), durationMs.toUInt()),
    )

    override fun getWebDavConfig(): WebDavConfigDto =
        saltJson.decodeFromString(engine.getWebdavConfigJson())

    override fun saveWebDavConfig(config: WebDavConfigDto) {
        engine.saveWebdavConfigJson(saltJson.encodeToString(config))
    }

    override fun syncWebDavNow(isMetered: Boolean): SyncReportDto =
        saltJson.decodeFromString(engine.syncWebdavWithPolicyJson(isMetered))

    override fun startLanP2p(enableMdns: Boolean): LanNodeInfoDto =
        saltJson.decodeFromString(engine.startLanP2p(enableMdns))

    override fun stopLanP2p() {
        engine.stopLanP2p()
    }

    override fun listLanPeers(): List<LanPeerInfoDto> =
        saltJson.decodeFromString(engine.listLanPeersJson())

    override fun syncWithLanPeer(
        host: String,
        port: Int,
        expectedFingerprint: String,
    ): P2pSyncResultDto = saltJson.decodeFromString(
        engine.syncWithLanPeerJson(host, port.toUShort(), expectedFingerprint),
    )

    override fun getSyncPrefs(): LocalSyncPrefsDto =
        saltJson.decodeFromString(engine.getSyncPrefsJson())

    override fun saveSyncPrefs(prefs: LocalSyncPrefsDto) {
        engine.saveSyncPrefsJson(saltJson.encodeToString(prefs))
    }

    override fun listPairedPeers(): List<PairedPeerDto> =
        saltJson.decodeFromString(engine.listPairedPeersJson())

    override fun removePairedPeer(deviceId: String) {
        engine.removePairedPeer(deviceId)
    }

    override fun setPairedPeerAutoSync(deviceId: String, enabled: Boolean) {
        engine.setPairedPeerAutoSync(deviceId, enabled)
    }

    override fun rotateLanPairingInfo(): LanNodeInfoDto =
        saltJson.decodeFromString(engine.rotateLanPairingInfoJson())

    override fun refreshLanNetwork(): LanNodeInfoDto? = runCatching {
        saltJson.decodeFromString<LanNodeInfoDto>(engine.refreshLanNetworkJson())
    }.getOrNull()

    override fun pairWithLanPeer(
        host: String,
        port: Int,
        expectedFingerprint: String,
        pairingSecret: String,
    ): P2pSyncResultDto = saltJson.decodeFromString(
        engine.pairWithLanPeerJson(host, port.toUShort(), expectedFingerprint, pairingSecret),
    )

    override fun syncAllPairedLanPeers(): LanBatchSyncReportDto =
        saltJson.decodeFromString(engine.syncAllPairedLanPeersJson())

    override fun captureInboxItem(input: InboxCaptureInputDto): String =
        inboxService.captureItem(
            InboxCaptureInput(
                kind = input.kind.toUniffi(),
                textNote = input.textNote,
                primaryMediaBytes = input.primaryMediaBytes,
                primaryMediaExt = input.primaryMediaExt,
                thumbWebpBytes = input.thumbWebpBytes,
                audioDurationMs = input.audioDurationMs.coerceAtLeast(0).toUInt(),
                isPrivate = input.isPrivate,
            ),
        )

    override fun listInboxItems(includeArchived: Boolean, limit: Int): List<InboxItemViewDto> =
        inboxService.listItems(includeArchived, limit.coerceAtLeast(1).toUInt()).map { it.toDto() }

    override suspend fun enrichInboxItem(inboxId: String): InboxItemViewDto =
        inboxService.enrichItem(inboxId).toDto()

    override fun consumeInboxIntoPage(inboxId: String, targetPageId: String, targetBlockId: String) {
        inboxService.consumeIntoPage(inboxId, targetPageId, targetBlockId)
    }

    override fun setInboxItemPrivate(inboxId: String, isPrivate: Boolean): InboxItemViewDto =
        inboxService.setPrivate(inboxId, isPrivate).toDto()

    override fun deleteInboxItem(inboxId: String) {
        inboxService.deleteItem(inboxId)
    }

    override suspend fun buildMicroReviewSession(
        budgetSeconds: Int,
        energyLevel: Int,
        rescueMode: Boolean,
    ): MicroSessionPlanDto {
        val plan = intelService.buildMicroReviewSession(
            budgetSeconds = budgetSeconds.coerceAtLeast(60).toUInt(),
            energyLevel = energyLevel.coerceIn(1, 3).toUByte(),
            rescueMode = rescueMode,
        )
        return MicroSessionPlanDto(
            sessionId = plan.sessionId,
            budgetSeconds = plan.budgetSeconds.toInt(),
            estimatedSeconds = plan.estimatedSeconds.toInt(),
            cards = plan.cards.map { it.toDto() },
            totalDueCount = plan.totalDueCount.toInt(),
        )
    }

    override fun redactLogText(raw: String): String = engine.redactLogText(raw)

    private fun wrapCallback(listener: HaloStreamListener): HaloStreamCallback =
        object : HaloStreamCallback {
            override fun onDelta(chunk: String) {
                listener.onDelta(chunk)
            }

            override fun onComplete(fullMarkdown: String, haloJson: String) {
                val parsed = runCatching {
                    saltJson.decodeFromString<HaloAnnotationDto>(haloJson)
                }.getOrNull()
                listener.onComplete(fullMarkdown, parsed)
            }

            override fun onError(code: String, message: String) {
                listener.onError(code, message)
            }
        }
}

private class NativeCancelHandle(private val handle: HaloCancelHandle) : CancelHandle {
    override fun cancel() = handle.cancel()
    override fun isCancelled(): Boolean = handle.isCancelled()
}

private fun InboxKindDto.toUniffi(): InboxKind = when (this) {
    InboxKindDto.QuickText -> InboxKind.QUICK_TEXT
    InboxKindDto.PhotoNote -> InboxKind.PHOTO_NOTE
    InboxKindDto.VoiceMemo -> InboxKind.VOICE_MEMO
    InboxKindDto.WebClip -> InboxKind.WEB_CLIP
}

private fun InboxKind.toDto(): InboxKindDto = when (this) {
    InboxKind.QUICK_TEXT -> InboxKindDto.QuickText
    InboxKind.PHOTO_NOTE -> InboxKindDto.PhotoNote
    InboxKind.VOICE_MEMO -> InboxKindDto.VoiceMemo
    InboxKind.WEB_CLIP -> InboxKindDto.WebClip
}

private fun InboxItemView.toDto(): InboxItemViewDto = InboxItemViewDto(
    inboxId = inboxId,
    kind = kind.toDto(),
    createdAtUtc = createdAtUtc,
    isPrivate = isPrivate,
    state = state,
    textNote = textNote,
    transcriptText = transcriptText,
    summaryText = summaryText,
    autoTags = autoTags,
    transcriptStatus = transcriptStatus,
    thumbAssetPath = thumbAssetPath,
    primaryAssetPath = primaryAssetPath,
    audioDurationMs = audioDurationMs.toInt(),
    consumedPageId = consumedPageId,
    consumedBlockId = consumedBlockId,
)

private fun ReviewCardView.toDto(): ReviewCardViewDto = ReviewCardViewDto(
    cardId = cardId,
    pageId = pageId,
    anchorHaloId = anchorHaloId,
    front = front,
    back = back,
    stability = stability,
    difficulty = difficulty,
    reps = reps.toInt(),
    lapses = lapses.toInt(),
    state = state.toInt(),
    lastReviewUtc = lastReviewUtc,
    dueAtUtc = dueAtUtc,
    retrievability = retrievability,
    sourcePageTitle = sourcePageTitle,
    sliceAssetPath = sliceAssetPath,
    contextExcerpt = contextExcerpt,
)
