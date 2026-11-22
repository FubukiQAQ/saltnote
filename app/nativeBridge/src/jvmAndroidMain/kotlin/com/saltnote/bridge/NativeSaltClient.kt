package com.saltnote.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.saltnote_ffi.FfiException
import uniffi.saltnote_ffi.HaloCancelHandle
import uniffi.saltnote_ffi.HaloStreamCallback
import uniffi.saltnote_ffi.PlatformSecretStore
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

    override fun getSyncRevision(): Long = engine.getSyncRevision().toLong()

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

    override fun syncWebDavNow(): SyncReportDto =
        saltJson.decodeFromString(engine.syncWebdavNowJson())

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
