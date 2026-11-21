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
import com.saltnote.ui.platform.setLanMulticastLock
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

    // Notes & Search
    val pages = mutableStateListOf<PageSummaryDto>()
    var activePage by mutableStateOf<PageSnapshotDto?>(null)
    var editableBody by mutableStateOf("")
    var modeFilter by mutableStateOf<String?>(null)
    var searchQuery by mutableStateOf("")
    val searchHits = mutableStateListOf<SearchHitDto>()

    // Canvas State
    var activeCanvasId by mutableStateOf("main")
    var selectedTool by mutableStateOf("pen")
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
                    showToast("已收到局域网同步更新")
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
                client = newClient
                activePage = null
                activeCrystal = null
                bootstrapVault(checkOnboarding = false)
                showToast("已切换笔记目录")
            }.onFailure { err ->
                showToast("切换目录失败：${err.message}")
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
                routerConfig = second.third
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

    fun createNewPage(title: String, mode: String, isPrivate: Boolean) {
        scope.launch {
            flushPendingBodyImmediately()
            val created = withContext(Dispatchers.IO) {
                client.createPage(
                    title = title.ifBlank { if (mode == "journal") "手账灵感页" else "学习笔记" },
                    mode = mode,
                    isPrivate = isPrivate,
                    tags = listOf(if (mode == "journal") "手账" else "学习"),
                    initialMarkdown = "# ${title.ifBlank { "新笔记" }}\n\n",
                )
            }
            refreshAll()
            openPage(created.pageId)
            editorMode = if (mode == "journal") EditorViewMode.Canvas else EditorViewMode.Document
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
            showToast(if (next) "已设为私密笔记" else "已取消私密保护")
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

        val argb = (
            ((inkColor.alpha * 255).toInt() and 0xFF).toLong() shl 24 or
                (((inkColor.red * 255).toInt() and 0xFF).toLong() shl 16) or
                (((inkColor.green * 255).toInt() and 0xFF).toLong() shl 8) or
                ((inkColor.blue * 255).toInt() and 0xFF).toLong()
            )
        val width = strokeWidth

        scope.launch {
            val ingested = withContext(Dispatchers.IO) {
                runCatching {
                    client.ingestRawStroke(pid, canvasId, effectiveTool, argb, width, points)
                }.getOrNull()
            }
            if (ingested != null) {
                if (canvasId == activeCanvasId) {
                    canvasStrokes.add(ingested.renderStroke)
                }
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { client.getPageSnapshot(pid) }.getOrNull()
                }
                if (refreshed != null) activePage = refreshed
                refreshPageListOnly()
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

    fun insertImageToActiveCanvas(rawBytes: ByteArray) {
        val pid = activePage?.meta?.pageId ?: return
        val worldX = (120f - panX) / zoom
        val worldY = (120f - panY) / zoom
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.insertCanvasImage(pid, activeCanvasId, rawBytes, worldX, worldY, 420f)
                }
            }.onSuccess {
                activePage = withContext(Dispatchers.IO) { client.getPageSnapshot(pid) }
                showToast("已插入图片")
            }.onFailure { err ->
                showToast("插图失败：${err.message}")
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
                        "该笔记已设为私密，仅允许使用本地或局域网模型。"
                    } else {
                        err.message ?: "请求失败"
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
                        "该笔记已设为私密，仅允许使用本地或局域网模型。"
                    } else {
                        err.message ?: "请求失败"
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
                            "该笔记已设为私密，仅允许使用本地或局域网模型。"
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
                showToast("已插入正文")
            }
        }
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
            showToast("已添加 ${cur.cardDrafts.size} 张复习卡片")
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
            showToast("已添加卡片")
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
                showToast("模型配置已保存")
            }.onFailure { err ->
                showToast("保存失败：${err.message}")
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
            showToast("密钥已保存")
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
                    ConnectivityTestResultDto(false, 0, it.message ?: "连接失败")
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
                    ConnectivityTestResultDto(false, 0, it.message ?: "连接失败")
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
                showToast("WebDAV 配置已保存")
            }.onFailure { err ->
                showToast("保存失败：${err.message}")
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
                showToast("同步完成：上传 ${report.uploadedDeltas} · 拉取 ${report.downloadedDeltas}")
            }.onFailure { err ->
                showToast("同步失败：${err.message}")
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
                    showToast("局域网同步已开启")
                }.onFailure { err ->
                    setLanMulticastLock(false)
                    showToast("开启失败：${err.message}")
                }
            } else {
                lanObserverJob?.cancel()
                withContext(Dispatchers.IO) { client.stopLanP2p() }
                setLanMulticastLock(false)
                lanNodeInfo = null
                lanPeers.clear()
                showToast("局域网同步已关闭")
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
                showToast("同步完成：发送 ${res.sentPackets} 篇 · 接收 ${res.receivedPackets} 篇")
            }.onFailure { err ->
                showToast("同步失败：${err.message}")
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
                showToast("已导出 $count 篇笔记至 $targetDir")
            }.onFailure { err ->
                showToast("导出失败：${err.message}")
            }
        }
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
