package com.saltnote.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.saltnote.bridge.CancelHandle
import com.saltnote.bridge.ConnectivityTestResultDto
import com.saltnote.bridge.DeviceStateDto
import com.saltnote.bridge.FlashcardDraftDto
import com.saltnote.bridge.FsrsCardStateDto
import com.saltnote.bridge.HaloAnnotationDto
import com.saltnote.bridge.HaloPresetDto
import com.saltnote.bridge.HaloStreamListener
import com.saltnote.bridge.LanNodeInfoDto
import com.saltnote.bridge.LanPeerInfoDto
import com.saltnote.bridge.PageSnapshotDto
import com.saltnote.bridge.PageSummaryDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.RouterConfigDto
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.SearchHitDto
import com.saltnote.bridge.StrokeSample
import com.saltnote.bridge.SyncReportDto
import com.saltnote.bridge.WebDavConfigDto
import com.saltnote.bridge.openSaltNoteClient
import com.saltnote.ui.components.LanPairingCodec
import com.saltnote.ui.components.StrokeGeometry
import com.saltnote.ui.i18n.AppLanguage
import com.saltnote.ui.i18n.SaltStrings
import com.saltnote.ui.i18n.resolveAiOutputLanguageCode
import com.saltnote.ui.i18n.resolveAppLanguage
import com.saltnote.ui.i18n.stringsFor
import com.saltnote.ui.platform.saveLastVaultDirectory
import com.saltnote.ui.platform.setLanMulticastLock
import com.saltnote.ui.platform.systemLanguageTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavTab(val label: String) {
    Notes("笔记"),
    Review("复习"),
    Sync("同步"),
    Settings("设置"),
}

enum class EditorViewMode(val label: String) {
    Canvas("画布模式"),
    Document("文档模式"),
}

data class ActiveCrystalBubble(
    val presetId: String,
    val presetName: String,
    val accentHex: String,
    val isStreaming: Boolean = false,
    val isCollapsed: Boolean = false,
    val markdown: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val persistedHalo: HaloAnnotationDto? = null,
    val cardDrafts: List<FlashcardDraftDto> = emptyList(),
    val fused: Boolean = false,
    val cardsImported: Boolean = false,
)

class SaltAppState(
    initialClient: SaltNoteClient,
    private val deviceName: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var client: SaltNoteClient by mutableStateOf(initialClient)
        private set

    var darkTheme by mutableStateOf(false)
    var showOnboarding by mutableStateOf(false)
    var selectedTab by mutableStateOf(NavTab.Notes)
    var editorMode by mutableStateOf(EditorViewMode.Canvas)

    var snackbarMessage by mutableStateOf<String?>(null)
    var deviceState by mutableStateOf(DeviceStateDto())

    // Multi-language settings
    var uiLanguage by mutableStateOf("system")
    var aiOutputLanguage by mutableStateOf("follow_ui")

    val activeAppLanguage: AppLanguage
        get() = resolveAppLanguage(uiLanguage, systemLanguageTag())

    val strings: SaltStrings
        get() = stringsFor(activeAppLanguage)

    val effectiveAiLanguageCode: String
        get() = resolveAiOutputLanguageCode(aiOutputLanguage, uiLanguage, systemLanguageTag())

    // Notes & Search
    val pages = mutableStateListOf<PageSummaryDto>()
    var activePage by mutableStateOf<PageSnapshotDto?>(null)
    var editableBody by mutableStateOf("")
    var modeFilter by mutableStateOf<String?>(null)
    var tagFilter by mutableStateOf<String?>(null)
    var searchQuery by mutableStateOf("")
    val searchHits = mutableStateListOf<SearchHitDto>()
    private var optimisticStrokeSeq: Long = 0L

    // Canvas State
    var activeCanvasId by mutableStateOf("main")
    var selectedTool by mutableStateOf("pen")
    var selectedImageId by mutableStateOf<String?>(null)
    var inkColor by mutableStateOf(Color(0xFF191C1E))
    var strokeWidth by mutableStateOf(3.2f)
    var stylusOnlyMode by mutableStateOf(false)
    var panX by mutableStateOf(0f)
    var panY by mutableStateOf(0f)
    var zoom by mutableStateOf(1f)
    val canvasStrokes = mutableStateListOf<RenderStrokeDto>()
    var syncUiVersion by mutableStateOf(0L)

    // Salt Halo State
    val haloPresets = mutableStateListOf<HaloPresetDto>()
    var activeCrystal by mutableStateOf<ActiveCrystalBubble?>(null)
    private var activeCancelHandle: CancelHandle? = null

    // FSRS Review State
    val dueCards = mutableStateListOf<FsrsCardStateDto>()
    val allCards = mutableStateListOf<FsrsCardStateDto>()
    var currentCardRevealed by mutableStateOf(false)
    private var cardShownAtMs: Long = System.currentTimeMillis()

    // Settings / BYOK / Sync
    var routerConfig by mutableStateOf<RouterConfigDto?>(null)
    var routerSecretEpoch by mutableStateOf(0)
    var testingProviderId by mutableStateOf<String?>(null)
    val connectivityResults = mutableStateOf<Map<String, ConnectivityTestResultDto>>(emptyMap())
    var webDavConfig by mutableStateOf(WebDavConfigDto())
    var isSyncingWebDav by mutableStateOf(false)
    var isSyncingLan by mutableStateOf(false)
    var lastSyncReport by mutableStateOf<SyncReportDto?>(null)
    var lanNodeInfo by mutableStateOf<LanNodeInfoDto?>(null)
    val lanPeers = mutableStateListOf<LanPeerInfoDto>()

    private var bodySaveJob: Job? = null
    private var pendingBodyDirty: Boolean = false
    private var periodicFlushJob: Job? = null
    private var lanObserverJob: Job? = null
    private var lastObservedSyncRevision: Long = 0L

    init {
        bootstrapVault(checkOnboarding = true)
        startPeriodicDeltaFlusher()
    }

    private fun startPeriodicDeltaFlusher() {
        periodicFlushJob?.cancel()
        periodicFlushJob = scope.launch {
            while (isActive) {
                delay(8_000L)
                withContext(Dispatchers.IO) {
                    runCatching { client.flushDeltas() }
                }
            }
        }
    }

    private fun startLanObserver() {
        lanObserverJob?.cancel()
        lanObserverJob = scope.launch {
            while (isActive && lanNodeInfo != null) {
                val (peers, rev) = withContext(Dispatchers.IO) {
                    val p = runCatching { client.listLanPeers() }.getOrDefault(emptyList())
                    val r = runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                    p to r
                }
                lanPeers.clear()
                lanPeers.addAll(peers)

                if (rev > lastObservedSyncRevision) {
                    lastObservedSyncRevision = rev
                    syncUiVersion = rev
                    refreshAll()
                    activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                    showToast(strings.lanIncomingSyncToast)
                }
                delay(1_800L)
            }
        }
    }

    private fun bootstrapVault(checkOnboarding: Boolean) {
        scope.launch {
            val isFirstLaunch = withContext(Dispatchers.IO) {
                val existing = client.listPages()
                if (existing.isEmpty()) {
                    client.ensureWelcomePage()
                    true
                } else {
                    false
                }
            }
            lastObservedSyncRevision = withContext(Dispatchers.IO) {
                runCatching { client.getSyncRevision() }.getOrDefault(0L)
            }
            if (checkOnboarding && isFirstLaunch) {
                showOnboarding = true
            }
            refreshAll()
            val firstId = pages.firstOrNull()?.meta?.pageId
            if (firstId != null) {
                openPage(firstId)
            }
        }
    }

    fun switchVaultDirectory(newVaultPath: String) {
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.flushDeltas()
                    openSaltNoteClient(newVaultPath, deviceName, client.secretVault)
                }
            }.onSuccess { newClient ->
                saveLastVaultDirectory(newVaultPath)
                client = newClient
                activePage = null
                selectedImageId = null
                activeCrystal = null
                bootstrapVault(checkOnboarding = false)
                showToast(strings.vaultSwitchedToast)
            }.onFailure { err ->
                showToast("${strings.vaultSwitchFailedPrefix}${err.message}")
            }
        }
    }

    fun refreshAll() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val dev = runCatching { client.getDeviceState() }.getOrDefault(DeviceStateDto())
                val list = runCatching { client.listPages() }.getOrDefault(emptyList())
                val presets = runCatching { client.getHaloPresets() }.getOrDefault(emptyList())
                val due = runCatching { client.listDueCards() }.getOrDefault(emptyList())
                val all = runCatching { client.listAllCards() }.getOrDefault(emptyList())
                val rcfg = runCatching { client.getRouterConfig() }.getOrNull()
                val dav = runCatching { client.getWebDavConfig() }.getOrDefault(WebDavConfigDto())
                Triple(Triple(dev, list, presets), Triple(due, all, rcfg), dav)
            }.let { (first, second, dav) ->
                deviceState = first.first
                pages.clear()
                pages.addAll(first.second)
                haloPresets.clear()
                haloPresets.addAll(first.third)
                dueCards.clear()
                dueCards.addAll(second.first)
                allCards.clear()
                allCards.addAll(second.second)
                val rcfg = second.third
                routerConfig = rcfg
                if (rcfg != null) {
                    uiLanguage = rcfg.uiLanguage.ifBlank { "system" }
                    aiOutputLanguage = rcfg.aiOutputLanguage.ifBlank { "follow_ui" }
                }
                webDavConfig = dav
            }
        }
    }

    private suspend fun flushPendingBodyImmediately() {
        bodySaveJob?.cancel()
        val pid = activePage?.meta?.pageId
        if (pendingBodyDirty && pid != null) {
            pendingBodyDirty = false
            val snapshotBody = editableBody
            withContext(Dispatchers.IO) {
                runCatching { client.updatePageBody(pid, snapshotBody) }
            }
        }
    }

    private suspend fun reloadCurrentPageInPlace(pageId: String) {
        val snap = withContext(Dispatchers.IO) {
            runCatching { client.getPageSnapshot(pageId) }.getOrNull()
        } ?: return
        activePage = snap
        if (!pendingBodyDirty) {
            editableBody = snap.bodyMarkdown
        }
        reloadCanvasStrokes()
    }

    fun openPage(pageId: String, targetHaloId: String? = null) {
        scope.launch {
            flushPendingBodyImmediately()
            withContext(Dispatchers.IO) {
                runCatching { client.flushDeltas() }
            }
            val snap = withContext(Dispatchers.IO) {
                runCatching { client.getPageSnapshot(pageId) }.getOrNull()
            } ?: return@launch

            activePage = snap
            editableBody = snap.bodyMarkdown
            activeCanvasId = "main"
            selectedImageId = null
            if (selectedTool == "select") selectedTool = "pen"
            editorMode = if (snap.meta.mode == "journal") EditorViewMode.Canvas else EditorViewMode.Document
            reloadCanvasStrokes()

            if (targetHaloId != null) {
                val halo = snap.halos.firstOrNull { it.haloId == targetHaloId }
                if (halo != null) {
                    openPersistedHaloBubble(halo)
                }
            }
        }
    }

    fun createNewPage(
        title: String,
        mode: String,
        isPrivate: Boolean,
        customTags: List<String> = emptyList(),
    ) {
        scope.launch {
            flushPendingBodyImmediately()
            val cleanedTags = customTags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
            val finalTags = if (cleanedTags.isNotEmpty()) {
                cleanedTags
            } else {
                listOf(if (mode == "journal") strings.filterJournal else strings.filterStudy)
            }
            val fallbackTitle = if (mode == "journal") strings.journalCanvas else strings.studyNote
            val resolvedTitle = title.ifBlank { fallbackTitle }
            val created = withContext(Dispatchers.IO) {
                client.createPage(
                    title = resolvedTitle,
                    mode = mode,
                    isPrivate = isPrivate,
                    tags = finalTags,
                    initialMarkdown = "# $resolvedTitle\n\n",
                )
            }
            refreshAll()
            openPage(created.pageId)
            editorMode = if (mode == "journal") EditorViewMode.Canvas else EditorViewMode.Document
        }
    }

    fun updatePageTags(newTags: List<String>) {
        val page = activePage ?: return
        val cleaned = newTags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client.updatePageMeta(page.meta.pageId, tags = cleaned) }
            }
            activePage = page.copy(meta = page.meta.copy(tags = cleaned))
            refreshPageListOnly()
        }
    }

    fun deletePage(pageId: String) {
        scope.launch {
            if (activePage?.meta?.pageId == pageId) {
                bodySaveJob?.cancel()
                pendingBodyDirty = false
                closeCrystalBubble()
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    client.deletePage(pageId)
                    client.flushDeltas()
                }
            }.onSuccess {
                refreshAll()
                val remaining = withContext(Dispatchers.IO) {
                    runCatching { client.listPages() }.getOrDefault(emptyList())
                }
                pages.clear()
                pages.addAll(remaining)
                if (activePage?.meta?.pageId == pageId) {
                    val nextId = remaining.firstOrNull()?.meta?.pageId
                    if (nextId != null) {
                        openPage(nextId)
                    } else {
                        activePage = null
                        selectedImageId = null
                        editableBody = ""
                        canvasStrokes.clear()
                    }
                }
                refreshCardsOnly()
                showToast(strings.noteDeletedToast)
            }.onFailure { err ->
                showToast("${strings.deleteFailedPrefix}${err.message}")
            }
        }
    }

    fun notifyInlineStrokePersisted() {
        val pid = activePage?.meta?.pageId ?: return
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                runCatching { client.getPageSnapshot(pid) }.getOrNull()
            }
            if (refreshed != null) activePage = refreshed
            refreshPageListOnly()
        }
    }

    fun updatePageTitle(newTitle: String) {
        val pid = activePage?.meta?.pageId ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client.updatePageMeta(pid, title = newTitle) }
            }
            activePage = activePage?.copy(meta = activePage!!.meta.copy(title = newTitle))
            refreshPageListOnly()
        }
    }

    fun togglePagePrivate() {
        val page = activePage ?: return
        val next = !page.meta.isPrivate
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client.updatePageMeta(page.meta.pageId, isPrivate = next) }
            }
            activePage = page.copy(meta = page.meta.copy(isPrivate = next))
            refreshPageListOnly()
            showToast(if (next) strings.privateSetToast else strings.privateUnsetToast)
        }
    }

    fun onBodyMarkdownEdited(newMarkdown: String) {
        editableBody = newMarkdown
        pendingBodyDirty = true
        val pid = activePage?.meta?.pageId ?: return
        bodySaveJob?.cancel()
        bodySaveJob = scope.launch {
            delay(800L)
            pendingBodyDirty = false
            withContext(Dispatchers.IO) {
                runCatching { client.updatePageBody(pid, newMarkdown) }
            }
            refreshPageListOnly()
        }
    }

    fun insertInlineCanvasBlock() {
        val inlineId = "inline-${System.currentTimeMillis() % 100000}"
        val block = "\n\n```salt-canvas\n{\"canvas_id\":\"$inlineId\",\"height\":240}\n```\n\n"
        onBodyMarkdownEdited(editableBody + block)
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            searchHits.clear()
            return
        }
        scope.launch {
            // Ensure latest typed edits are indexed before searching
            flushPendingBodyImmediately()
            val hits = withContext(Dispatchers.IO) {
                runCatching { client.searchNotes(query, 30) }.getOrDefault(emptyList())
            }
            searchHits.clear()
            searchHits.addAll(hits)
        }
    }

    // ------------------------------------------------------------------------
    // Canvas WYSIWYG Ink, Eraser, Image Insert & Salt Halo Lasso
    // ------------------------------------------------------------------------

    fun updatePanZoom(dx: Float, dy: Float, zoomFactor: Float, pivotX: Float, pivotY: Float) {
        val newZoom = (zoom * zoomFactor).coerceIn(0.25f, 4.0f)
        val actualFactor = newZoom / zoom
        panX = pivotX - (pivotX - panX) * actualFactor + dx
        panY = pivotY - (pivotY - panY) * actualFactor + dy
        zoom = newZoom
    }

    fun resetViewport() {
        panX = 0f
        panY = 0f
        zoom = 1f
        reloadCanvasStrokes()
    }

    fun reloadCanvasStrokes(canvasId: String = activeCanvasId) {
        val pid = activePage?.meta?.pageId ?: return
        scope.launch {
            val strokes = withContext(Dispatchers.IO) {
                runCatching {
                    client.getCanvasStrokes(
                        pageId = pid,
                        canvasId = canvasId,
                        minX = -50_000f,
                        minY = -50_000f,
                        maxX = 50_000f,
                        maxY = 50_000f,
                    )
                }.getOrDefault(emptyList())
            }
            if (canvasId == activeCanvasId) {
                canvasStrokes.clear()
                canvasStrokes.addAll(strokes)
            }
        }
    }

    fun onCanvasStrokeCompleted(canvasId: String, points: List<StrokeSample>, forcedBrush: String? = null) {
        val pid = activePage?.meta?.pageId ?: return
        if (points.isEmpty()) return

        val effectiveTool = forcedBrush ?: selectedTool
        val haloPresetId = when (effectiveTool) {
            "halo_amber" -> "builtin.amber_expand"
            "halo_glacier" -> "builtin.glacier_card"
            "halo_mineral" -> "builtin.mineral_ocr"
            else -> null
        }
        if (haloPresetId != null) {
            triggerCanvasHalo(pid, canvasId, haloPresetId, points)
            return
        }

        val effectiveColor = when (effectiveTool) {
            "highlighter" -> if (inkColor == Color(0xFF191C1E)) Color(0xFFF5C242) else inkColor
            else -> inkColor
        }
        val argb = (
            ((effectiveColor.alpha * 255).toInt() and 0xFF).toLong() shl 24 or
                (((effectiveColor.red * 255).toInt() and 0xFF).toLong() shl 16) or
                (((effectiveColor.green * 255).toInt() and 0xFF).toLong() shl 8) or
                ((effectiveColor.blue * 255).toInt() and 0xFF).toLong()
            )
        val width = strokeWidth

        // Synchronously insert optimistic dry-ink stroke on Main thread BEFORE wet ink clears -> zero blink!
        val tempId = "pending-main-${++optimisticStrokeSeq}"
        if (canvasId == activeCanvasId) {
            val optimistic = StrokeGeometry.buildOptimisticRenderStroke(
                tempId = tempId,
                brush = effectiveTool,
                colorArgb = argb,
                baseWidth = width,
                points = points,
            )
            canvasStrokes.add(optimistic)
        }

        scope.launch {
            val ingested = withContext(Dispatchers.IO) {
                runCatching {
                    client.ingestRawStroke(pid, canvasId, effectiveTool, argb, width, points)
                }.getOrNull()
            }
            if (ingested != null) {
                if (canvasId == activeCanvasId) {
                    val idx = canvasStrokes.indexOfFirst { it.strokeId == tempId }
                    if (idx >= 0) {
                        canvasStrokes[idx] = ingested.renderStroke
                    } else {
                        canvasStrokes.add(ingested.renderStroke)
                    }
                }
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { client.getPageSnapshot(pid) }.getOrNull()
                }
                if (refreshed != null) activePage = refreshed
                refreshPageListOnly()
            } else if (canvasId == activeCanvasId) {
                canvasStrokes.removeAll { it.strokeId == tempId }
            }
        }
    }

    fun onEraserMove(canvasId: String, worldX: Float, worldY: Float, radiusOverride: Float? = null) {
        val pid = activePage?.meta?.pageId ?: return
        val radius = radiusOverride ?: (16f / zoom.coerceAtLeast(0.5f))
        scope.launch {
            val erased = withContext(Dispatchers.IO) {
                runCatching {
                    client.eraseStrokesAt(pid, canvasId, worldX, worldY, radius)
                }.getOrDefault(emptyList())
            }
            if (erased.isNotEmpty()) {
                if (canvasId == activeCanvasId) {
                    canvasStrokes.removeAll { it.strokeId in erased }
                }
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { client.getPageSnapshot(pid) }.getOrNull()
                }
                if (refreshed != null) activePage = refreshed
                refreshPageListOnly()
            }
        }
    }

    fun selectTool(tool: String) {
        selectedTool = tool
        if (tool != "select") {
            selectedImageId = null
        }
    }

    fun insertImageToActiveCanvas(rawBytes: ByteArray) {
        val pid = activePage?.meta?.pageId ?: return
        val targetMaxWidth = 340f
        val baseX = (96f - panX) / zoom
        val baseY = (96f - panY) / zoom
        val existing = activePage?.canvases
            ?.firstOrNull { it.canvasId == activeCanvasId }
            ?.images
            ?.filter { !it.deleted }
            .orEmpty()
        var worldX = baseX
        var worldY = baseY
        var attempts = 0
        while (attempts < 32) {
            val collisions = existing.filter { img ->
                val pad = 20f
                worldX < img.x + img.width + pad &&
                    worldX + targetMaxWidth + pad > img.x &&
                    worldY < img.y + img.height + pad &&
                    worldY + targetMaxWidth + pad > img.y
            }
            if (collisions.isEmpty()) break
            worldY = collisions.maxOf { it.y + it.height } + 24f
            attempts++
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.insertCanvasImage(pid, activeCanvasId, rawBytes, worldX, worldY, targetMaxWidth)
                }
            }.onSuccess { inserted ->
                activePage = withContext(Dispatchers.IO) { client.getPageSnapshot(pid) }
                selectedImageId = inserted.imageId
                selectedTool = "select"
                showToast(strings.imageInsertedToast)
            }.onFailure { err ->
                showToast("${strings.imageInsertFailedPrefix}${err.message}")
            }
        }
    }

    fun updateCanvasImageTransform(
        imageId: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        deleted: Boolean = false,
    ) {
        val page = activePage ?: return
        val pid = page.meta.pageId
        activePage = page.copy(
            canvases = page.canvases.map { canvas ->
                if (canvas.canvasId == activeCanvasId) {
                    canvas.copy(
                        images = canvas.images.map { img ->
                            if (img.imageId == imageId) {
                                img.copy(x = x, y = y, width = width, height = height, deleted = deleted)
                            } else {
                                img
                            }
                        },
                    )
                } else {
                    canvas
                }
            },
        )
        if (deleted && selectedImageId == imageId) {
            selectedImageId = null
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.updateCanvasImageTransform(
                        pageId = pid,
                        canvasId = activeCanvasId,
                        imageId = imageId,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        deleted = deleted,
                    )
                }
            }.onSuccess { updated ->
                val cur = activePage
                if (cur != null && cur.meta.pageId == pid) {
                    activePage = cur.copy(
                        canvases = cur.canvases.map { canvas ->
                            if (canvas.canvasId == activeCanvasId) {
                                canvas.copy(
                                    images = canvas.images.map { if (it.imageId == updated.imageId) updated else it },
                                )
                            } else {
                                canvas
                            }
                        },
                    )
                }
                refreshPageListOnly()
            }.onFailure { err ->
                showToast("${strings.updateFailedPrefix}${err.message}")
                reloadCurrentPageInPlace(pid)
            }
        }
    }

    fun moveHaloPin(haloId: String, pinX: Float, pinY: Float) {
        val page = activePage ?: return
        val pid = page.meta.pageId
        activePage = page.copy(
            halos = page.halos.map { h ->
                if (h.haloId == haloId) h.copy(pinX = pinX, pinY = pinY) else h
            },
        )
        activeCrystal?.let { cur ->
            if (cur.persistedHalo?.haloId == haloId) {
                activeCrystal = cur.copy(persistedHalo = cur.persistedHalo.copy(pinX = pinX, pinY = pinY))
            }
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.updateHaloPin(pid, haloId, pinX, pinY)
                }
            }.onFailure {
                reloadCurrentPageInPlace(pid)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Salt Halo Streaming, Cancel, Fuse & FSRS Card Import
    // ------------------------------------------------------------------------

    private fun triggerCanvasHalo(
        pageId: String,
        canvasId: String,
        presetId: String,
        lassoPoints: List<StrokeSample>,
    ) {
        cancelActiveCrystalStream()
        val preset = haloPresets.firstOrNull { it.presetId == presetId }
        activeCrystal = ActiveCrystalBubble(
            presetId = presetId,
            presetName = preset?.name ?: presetId,
            accentHex = preset?.colorHex ?: "#F5C242",
            isStreaming = true,
        )

        val listener = buildHaloStreamListener(pageId, presetId)
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.triggerCanvasHalo(
                        pageId = pageId,
                        canvasId = canvasId,
                        presetId = presetId,
                        lassoPoints = lassoPoints,
                        providerId = null,
                        outputLanguageHint = effectiveAiLanguageCode,
                        listener = listener,
                    )
                }
            }.onSuccess { handle ->
                activeCancelHandle = handle
            }.onFailure { err ->
                val isPrivacy = err::class.simpleName == "PrivacyBlocked" ||
                    err.message?.contains("PrivacyBlocked", ignoreCase = true) == true ||
                    err.message?.contains("#private", ignoreCase = true) == true
                activeCrystal = activeCrystal?.copy(
                    isStreaming = false,
                    errorCode = if (isPrivacy) "PRIVACY_BLOCKED" else "ROUTER_ERROR",
                    errorMessage = if (isPrivacy) {
                        strings.privateBlockError
                    } else {
                        err.message ?: strings.requestFailed
                    },
                )
            }
        }
    }

    fun triggerTextHalo(presetId: String, selectedText: String) {
        val pid = activePage?.meta?.pageId ?: return
        val excerpt = selectedText.ifBlank { editableBody.takeLast(240) }
        cancelActiveCrystalStream()
        val preset = haloPresets.firstOrNull { it.presetId == presetId }
        activeCrystal = ActiveCrystalBubble(
            presetId = presetId,
            presetName = preset?.name ?: presetId,
            accentHex = preset?.colorHex ?: "#F5C242",
            isStreaming = true,
        )

        val listener = buildHaloStreamListener(pid, presetId)
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.triggerTextHalo(
                        pageId = pid,
                        presetId = presetId,
                        anchorOffset = editableBody.length,
                        selectedText = excerpt,
                        providerId = null,
                        outputLanguageHint = effectiveAiLanguageCode,
                        listener = listener,
                    )
                }
            }.onSuccess { handle ->
                activeCancelHandle = handle
            }.onFailure { err ->
                val isPrivacy = err::class.simpleName == "PrivacyBlocked" ||
                    err.message?.contains("PrivacyBlocked", ignoreCase = true) == true ||
                    err.message?.contains("#private", ignoreCase = true) == true
                activeCrystal = activeCrystal?.copy(
                    isStreaming = false,
                    errorCode = if (isPrivacy) "PRIVACY_BLOCKED" else "ROUTER_ERROR",
                    errorMessage = if (isPrivacy) {
                        strings.privateBlockError
                    } else {
                        err.message ?: strings.requestFailed
                    },
                )
            }
        }
    }

    private fun buildHaloStreamListener(pageId: String, presetId: String): HaloStreamListener =
        object : HaloStreamListener {
            override fun onDelta(chunk: String) {
                scope.launch {
                    val cur = activeCrystal ?: return@launch
                    activeCrystal = cur.copy(markdown = cur.markdown + chunk)
                }
            }

            override fun onComplete(fullMarkdown: String, halo: HaloAnnotationDto?) {
                scope.launch {
                    val drafts = if (presetId == "builtin.glacier_card") {
                        withContext(Dispatchers.IO) { client.parseFlashcardDrafts(fullMarkdown) }
                    } else {
                        emptyList()
                    }
                    activeCrystal = activeCrystal?.copy(
                        isStreaming = false,
                        markdown = fullMarkdown,
                        persistedHalo = halo,
                        cardDrafts = drafts,
                    )
                    activeCancelHandle = null
                    activePage = withContext(Dispatchers.IO) {
                        runCatching { client.getPageSnapshot(pageId) }.getOrNull()
                    }
                }
            }

            override fun onError(code: String, message: String) {
                scope.launch {
                    activeCrystal = activeCrystal?.copy(
                        isStreaming = false,
                        errorCode = code,
                        errorMessage = if (code == "PRIVACY_BLOCKED") {
                            strings.privateBlockError
                        } else {
                            message
                        },
                    )
                    activeCancelHandle = null
                }
            }
        }

    fun openPersistedHaloBubble(halo: HaloAnnotationDto) {
        val preset = haloPresets.firstOrNull { it.presetId == halo.presetId }
        scope.launch {
            val drafts = if (halo.presetId == "builtin.glacier_card") {
                withContext(Dispatchers.IO) { client.parseFlashcardDrafts(halo.responseMarkdown) }
            } else {
                emptyList()
            }
            activeCrystal = ActiveCrystalBubble(
                presetId = halo.presetId,
                presetName = preset?.name ?: halo.presetId,
                accentHex = preset?.colorHex ?: "#F5C242",
                isStreaming = false,
                isCollapsed = halo.collapsed,
                markdown = halo.responseMarkdown,
                persistedHalo = halo,
                cardDrafts = drafts,
                fused = halo.fused,
            )
        }
    }

    fun toggleCrystalCollapsed() {
        val cur = activeCrystal ?: return
        val next = !cur.isCollapsed
        activeCrystal = cur.copy(isCollapsed = next)
        val halo = cur.persistedHalo ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { client.toggleHaloCollapsed(halo.pageId, halo.haloId, next) }
        }
    }

    fun closeCrystalBubble() {
        cancelActiveCrystalStream()
        activeCrystal = null
    }

    fun deleteActiveCrystalHalo() {
        val cur = activeCrystal ?: return
        val halo = cur.persistedHalo ?: return
        cancelActiveCrystalStream()
        activeCrystal = null
        activePage?.let { page ->
            if (page.meta.pageId == halo.pageId) {
                activePage = page.copy(
                    halos = page.halos.filterNot { it.haloId == halo.haloId },
                )
            }
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.deleteHalo(halo.pageId, halo.haloId)
                }
            }.onSuccess {
                refreshPageListOnly()
                showToast(strings.haloDeletedToast)
            }.onFailure { err ->
                showToast("${strings.deleteFailedPrefix}${err.message}")
                reloadCurrentPageInPlace(halo.pageId)
            }
        }
    }

    private fun cancelActiveCrystalStream() {
        activeCancelHandle?.cancel()
        activeCancelHandle = null
    }

    fun fuseActiveCrystalIntoBody() {
        val cur = activeCrystal ?: return
        val halo = cur.persistedHalo ?: return
        scope.launch {
            flushPendingBodyImmediately()
            val updatedBody = withContext(Dispatchers.IO) {
                runCatching { client.fuseHaloIntoBody(halo.pageId, halo.haloId) }.getOrNull()
            }
            if (updatedBody != null) {
                editableBody = updatedBody
                activeCrystal = cur.copy(fused = true, isCollapsed = true)
                activePage = withContext(Dispatchers.IO) { client.getPageSnapshot(halo.pageId) }
                showToast(strings.fusedToast)
            }
        }
    }

    fun removeCrystalDraftCard(index: Int) {
        val cur = activeCrystal ?: return
        if (index !in cur.cardDrafts.indices) return
        activeCrystal = cur.copy(
            cardDrafts = cur.cardDrafts.filterIndexed { i, _ -> i != index },
        )
    }

    fun importCrystalDraftCardsToFsrs() {
        val cur = activeCrystal ?: return
        val pageId = activePage?.meta?.pageId ?: return
        if (cur.cardDrafts.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (draft in cur.cardDrafts) {
                    client.createFlashcard(pageId, cur.persistedHalo?.haloId, draft.front, draft.back)
                }
            }
            activeCrystal = cur.copy(cardsImported = true)
            refreshCardsOnly()
            showToast("${strings.addedCardsToastPrefix}${cur.cardDrafts.size}${strings.addedCardsToastSuffix}")
        }
    }

    // ------------------------------------------------------------------------
    // FSRS Spaced Repetition Actions
    // ------------------------------------------------------------------------

    fun createManualFlashcard(front: String, back: String) {
        val pid = activePage?.meta?.pageId ?: pages.firstOrNull()?.meta?.pageId ?: return
        if (front.isBlank() || back.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                client.createFlashcard(pid, null, front.trim(), back.trim())
            }
            refreshCardsOnly()
            showToast(strings.cardAddedToast)
        }
    }

    fun updateFlashcard(pageId: String, cardId: String, front: String, back: String) {
        if (front.isBlank() || back.isBlank()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.updateFlashcard(pageId, cardId, front.trim(), back.trim())
                }
            }.onSuccess {
                refreshCardsOnly()
                if (activePage?.meta?.pageId == pageId) {
                    reloadCurrentPageInPlace(pageId)
                }
                showToast(strings.cardUpdatedToast)
            }.onFailure { err ->
                showToast("${strings.updateFailedPrefix}${err.message}")
            }
        }
    }

    fun deleteFlashcard(pageId: String, cardId: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.deleteFlashcard(pageId, cardId)
                }
            }.onSuccess {
                currentCardRevealed = false
                refreshCardsOnly()
                if (activePage?.meta?.pageId == pageId) {
                    reloadCurrentPageInPlace(pageId)
                }
                showToast(strings.cardDeletedToast)
            }.onFailure { err ->
                showToast("${strings.deleteFailedPrefix}${err.message}")
            }
        }
    }

    fun revealCurrentCard() {
        currentCardRevealed = true
    }

    fun submitReviewRating(card: FsrsCardStateDto, rating: Int) {
        val elapsedMs = (System.currentTimeMillis() - cardShownAtMs).coerceIn(500L, 600_000L).toInt()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client.submitCardReview(card.pageId, card.cardId, rating, elapsedMs) }
            }
            currentCardRevealed = false
            cardShownAtMs = System.currentTimeMillis()
            refreshCardsOnly()
        }
    }

    fun jumpToCardAnchor(card: FsrsCardStateDto) {
        selectedTab = NavTab.Notes
        openPage(card.pageId, targetHaloId = card.anchorHaloId)
    }

    // ------------------------------------------------------------------------
    // Settings, WebDAV Sync, LAN P2P & Markdown Backup
    // ------------------------------------------------------------------------

    private fun sanitizeRouterConfig(cfg: RouterConfigDto): RouterConfigDto =
        cfg.copy(
            uiLanguage = uiLanguage,
            aiOutputLanguage = aiOutputLanguage,
            providers = cfg.providers.map { p ->
                val firstUrlLine = p.baseUrl.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                val cleanUrl = firstUrlLine
                    .split(Regex("\\s+"))
                    .takeWhile { !it.startsWith("sk-") }
                    .joinToString("")
                    .trimEnd('/')
                val firstModelLine = p.model.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
                p.copy(
                    baseUrl = cleanUrl,
                    model = firstModelLine,
                    apiKeyRef = p.apiKeyRef.trim(),
                )
            },
        )

    fun updateLanguageSettings(newUiLang: String, newAiLang: String) {
        val normalizedUi = newUiLang.ifBlank { "system" }
        val normalizedAi = newAiLang.ifBlank { "follow_ui" }
        uiLanguage = normalizedUi
        aiOutputLanguage = normalizedAi
        val currentCfg = routerConfig
        if (currentCfg != null) {
            routerConfig = sanitizeRouterConfig(currentCfg)
        }
        scope.launch {
            runCatching {
                val merged = withContext(Dispatchers.IO) {
                    val base = currentCfg ?: client.getRouterConfig()
                    val clean = sanitizeRouterConfig(base)
                    client.saveRouterConfig(clean)
                    clean
                }
                routerConfig = merged
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun saveRouterConfig(cfg: RouterConfigDto, unsavedSecrets: Map<String, String> = emptyMap()) {
        val clean = sanitizeRouterConfig(cfg)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    unsavedSecrets.forEach { (keyRef, rawSecret) ->
                        val secret = rawSecret.replace("\n", "").replace("\r", "").trim()
                        if (keyRef.isNotBlank() && secret.isNotEmpty()) {
                            client.secretVault.putSecret(keyRef, secret)
                        }
                    }
                    client.saveRouterConfig(clean)
                }
            }.onSuccess {
                routerConfig = clean
                if (unsavedSecrets.isNotEmpty()) {
                    routerSecretEpoch++
                }
                showToast(strings.configSavedToast)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun saveProviderSecret(keyRef: String, plainSecret: String) {
        if (keyRef.isBlank()) return
        val cleanSecret = plainSecret.replace("\n", "").replace("\r", "").trim()
        scope.launch {
            withContext(Dispatchers.IO) {
                if (cleanSecret.isBlank()) {
                    client.secretVault.deleteSecret(keyRef)
                } else {
                    client.secretVault.putSecret(keyRef, cleanSecret)
                }
            }
            routerSecretEpoch++
            showToast(strings.keySavedToast)
        }
    }

    fun saveAndTestProvider(
        cfg: RouterConfigDto,
        providerId: String,
        unsavedSecrets: Map<String, String>,
    ) {
        if (testingProviderId != null) return
        val clean = sanitizeRouterConfig(cfg)
        testingProviderId = providerId
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    unsavedSecrets.forEach { (keyRef, rawSecret) ->
                        val secret = rawSecret.replace("\n", "").replace("\r", "").trim()
                        if (keyRef.isNotBlank() && secret.isNotEmpty()) {
                            client.secretVault.putSecret(keyRef, secret)
                        }
                    }
                    client.saveRouterConfig(clean)
                    client.testProviderConnection(providerId)
                }.getOrElse {
                    ConnectivityTestResultDto(false, 0, it.message ?: strings.connectionFailed)
                }
            }
            routerConfig = clean
            routerSecretEpoch++
            connectivityResults.value = connectivityResults.value + (providerId to res)
            testingProviderId = null
        }
    }

    fun testProviderConnectivity(providerId: String) {
        if (testingProviderId != null) return
        testingProviderId = providerId
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { client.testProviderConnection(providerId) }.getOrElse {
                    ConnectivityTestResultDto(false, 0, it.message ?: strings.connectionFailed)
                }
            }
            connectivityResults.value = connectivityResults.value + (providerId to res)
            testingProviderId = null
        }
    }

    fun saveWebDavConfig(cfg: WebDavConfigDto, optionalPassword: String?) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!optionalPassword.isNullOrBlank() && cfg.passwordKeyRef.isNotBlank()) {
                        client.secretVault.putSecret(cfg.passwordKeyRef, optionalPassword)
                    }
                    client.saveWebDavConfig(cfg)
                }
            }.onSuccess {
                webDavConfig = cfg
                showToast(strings.webdavSavedToast)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun triggerWebDavSync() {
        if (isSyncingWebDav) return
        isSyncingWebDav = true
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) { client.syncWebDavNow() }
            }.onSuccess { report ->
                lastSyncReport = report
                syncUiVersion++
                refreshAll()
                activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                showToast("${strings.lanSyncedToast} (↑${report.uploadedDeltas} · ↓${report.downloadedDeltas})")
            }.onFailure { err ->
                showToast("${strings.syncFailedPrefix}${err.message}")
            }
            isSyncingWebDav = false
        }
    }

    fun toggleLanP2p(enable: Boolean) {
        scope.launch {
            if (enable) {
                setLanMulticastLock(true)
                runCatching {
                    withContext(Dispatchers.IO) { client.startLanP2p(enableMdns = true) }
                }.onSuccess { info ->
                    lanNodeInfo = info
                    lastObservedSyncRevision = withContext(Dispatchers.IO) {
                        runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                    }
                    refreshLanPeers()
                    startLanObserver()
                    showToast(strings.lanSyncStartedToast)
                }.onFailure { err ->
                    setLanMulticastLock(false)
                    showToast("${strings.syncFailedPrefix}${err.message}")
                }
            } else {
                lanObserverJob?.cancel()
                withContext(Dispatchers.IO) { client.stopLanP2p() }
                setLanMulticastLock(false)
                lanNodeInfo = null
                lanPeers.clear()
                showToast(strings.lanSyncStoppedToast)
            }
        }
    }

    fun refreshLanPeers() {
        scope.launch {
            val peers = withContext(Dispatchers.IO) {
                runCatching { client.listLanPeers() }.getOrDefault(emptyList())
            }
            lanPeers.clear()
            lanPeers.addAll(peers)
        }
    }

    fun connectFromPairingPayload(raw: String): Boolean {
        val parsed = LanPairingCodec.parse(raw)
        if (parsed == null) {
            showToast(strings.invalidPairingToast)
            return false
        }
        syncWithLanPeer(parsed.host, parsed.port, parsed.fingerprint)
        return true
    }

    fun syncWithLanPeer(host: String, port: Int, fingerprint: String) {
        if (isSyncingLan) return
        isSyncingLan = true
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.flushDeltas()
                    client.syncWithLanPeer(host, port, fingerprint)
                }
            }.onSuccess { res ->
                lastObservedSyncRevision = withContext(Dispatchers.IO) {
                    runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                }
                syncUiVersion++
                refreshAll()
                activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                val msg = if (res.sentPackets == 0 && res.receivedPackets == 0 && res.sentAssets == 0 && res.receivedAssets == 0) {
                    strings.lanUpToDateToast
                } else {
                    "${strings.lanSyncedToast} (↑${res.sentPackets + res.sentAssets} · ↓${res.receivedPackets + res.receivedAssets})"
                }
                showToast(msg)
            }.onFailure { err ->
                showToast("${strings.syncFailedPrefix}${err.message}")
            }
            isSyncingLan = false
        }
    }

    fun exportMarkdownBackup(targetDir: String) {
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) { client.exportMarkdownBackup(targetDir) }
            }.onSuccess { count ->
                showToast("${strings.backupExportedToast} ($count)")
            }.onFailure { err ->
                showToast("${strings.backupFailedPrefix}${err.message}")
            }
        }
    }

    fun notifyMessage(msg: String) {
        showToast(msg)
    }

    fun clearToast() {
        snackbarMessage = null
    }

    private fun showToast(msg: String) {
        snackbarMessage = msg
    }

    private fun refreshPageListOnly() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { client.listPages() }.getOrDefault(emptyList())
            }
            pages.clear()
            pages.addAll(list)
        }
    }

    private fun refreshCardsOnly() {
        scope.launch {
            val (due, all) = withContext(Dispatchers.IO) {
                val d = runCatching { client.listDueCards() }.getOrDefault(emptyList())
                val a = runCatching { client.listAllCards() }.getOrDefault(emptyList())
                d to a
            }
            dueCards.clear()
            dueCards.addAll(due)
            allCards.clear()
            allCards.addAll(all)
        }
    }
}
