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
import com.saltnote.bridge.InboxCaptureInputDto
import com.saltnote.bridge.InboxItemViewDto
import com.saltnote.bridge.InboxKindDto
import com.saltnote.bridge.LanNodeInfoDto
import com.saltnote.bridge.LanPeerInfoDto
import com.saltnote.bridge.LocalSyncPrefsDto
import com.saltnote.bridge.MicroSessionPlanDto
import com.saltnote.bridge.PageSnapshotDto
import com.saltnote.bridge.PageSummaryDto
import com.saltnote.bridge.PairedPeerDto
import com.saltnote.bridge.RenderStrokeDto
import com.saltnote.bridge.ReviewCardViewDto
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
import com.saltnote.ui.platform.PlatformAudioPlaybackHandle
import com.saltnote.ui.platform.PlatformNetworkState
import com.saltnote.ui.platform.RecordedAudioClip
import com.saltnote.ui.platform.currentNetworkState
import com.saltnote.ui.platform.observeNetworkState
import com.saltnote.ui.platform.playVaultAudio
import com.saltnote.ui.platform.readVaultAssetBytes
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
    Hub("盐池"),
    Notes("笔记"),
    Review("复习"),
    Sync("同步"),
    Settings("设置"),
}

enum class LaunchRoute {
    None,
    CameraCapture,
    VoiceCapture,
    MicroReview,
}

enum class EditorViewMode(val label: String) {
    Canvas("画布模式"),
    Document("文档模式"),
}

enum class AutoSyncStateKind {
    Idle,
    Syncing,
    Synced,
    PendingOffline,
    Error,
}

data class AutoSyncStatus(
    val kind: AutoSyncStateKind = AutoSyncStateKind.Idle,
    val lastSyncedUtcMs: Long = 0L,
    val detail: String = "",
)

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

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
    var initialTabResolved by mutableStateOf(false)
    var editorMode by mutableStateOf(EditorViewMode.Canvas)

    // v0.2-alpha Adaptive Modals, Salt Tray & Widget/Tile Launch Triggers
    var showSaltTray by mutableStateOf(true)
    var showCameraCaptureModal by mutableStateOf(false)
    var showVoiceCaptureModal by mutableStateOf(false)
    var showQuickTextCaptureModal by mutableStateOf(false)
    var showCompactSyncModal by mutableStateOf(false)
    var showCompactSettingsModal by mutableStateOf(false)
    var pendingLaunchRoute by mutableStateOf(LaunchRoute.None)

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

    // FSRS Review & v0.2 Micro-Session State
    val dueCards = mutableStateListOf<FsrsCardStateDto>()
    val allCards = mutableStateListOf<FsrsCardStateDto>()
    var currentCardRevealed by mutableStateOf(false)
    private var cardShownAtMs: Long = System.currentTimeMillis()
    var microSessionPlan by mutableStateOf<MicroSessionPlanDto?>(null)
    var microSessionIndex by mutableStateOf(0)
    var microSessionBudgetSecs by mutableStateOf(300)
    var microSessionRescueMode by mutableStateOf(true)
    var microSessionEnergyLevel by mutableStateOf(2)
    var geekSwipeEnabled by mutableStateOf(true)
    var isMicroSessionFinished by mutableStateOf(false)
    var activeSlicePreviewCard by mutableStateOf<ReviewCardViewDto?>(null)

    // Salt Inbox (盐池) State
    val inboxItems = mutableStateListOf<InboxItemViewDto>()
    var showArchivedInbox by mutableStateOf(false)
    val enrichingInboxIds = mutableStateListOf<String>()
    var playingAudioInboxId by mutableStateOf<String?>(null)
    private var activeAudioHandle: PlatformAudioPlaybackHandle? = null

    // Settings / BYOK / Sync / Metered Guard / Auto-Sync
    var routerConfig by mutableStateOf<RouterConfigDto?>(null)
    var routerSecretEpoch by mutableStateOf(0)
    var testingProviderId by mutableStateOf<String?>(null)
    val connectivityResults = mutableStateOf<Map<String, ConnectivityTestResultDto>>(emptyMap())
    var webDavConfig by mutableStateOf(WebDavConfigDto())
    var syncPrefs by mutableStateOf(LocalSyncPrefsDto())
    var networkState by mutableStateOf(currentNetworkState())
    var isMeteredNetwork by mutableStateOf(networkState.isMetered)
    var isSyncingWebDav by mutableStateOf(false)
    var isSyncingLan by mutableStateOf(false)
    var lastSyncReport by mutableStateOf<SyncReportDto?>(null)
    var lanNodeInfo by mutableStateOf<LanNodeInfoDto?>(null)
    val lanPeers = mutableStateListOf<LanPeerInfoDto>()
    val pairedPeers = mutableStateListOf<PairedPeerDto>()
    var autoSyncStatus by mutableStateOf(AutoSyncStatus())

    private var bodySaveJob: Job? = null
    private var pendingBodyDirty: Boolean = false
    private var periodicFlushJob: Job? = null
    private var lanObserverJob: Job? = null
    private var autoSyncJob: Job? = null
    private var isAutoSyncExecuting: Boolean = false
    private var localDirtyEpoch: Long = 0L
    private var lastSyncedEpoch: Long = 0L
    private var networkObserverDispose: (() -> Unit)? = null
    private var lastObservedSyncRevision: Long = 0L
    private var lastObservedInboxRevision: Long = 0L
    private var knownOnlinePairedIds: Set<String> = emptySet()

    init {
        bootstrapVault(checkOnboarding = true)
        startPeriodicDeltaFlusher()
        networkObserverDispose = observeNetworkState { newState ->
            scope.launch {
                val prev = networkState
                networkState = newState
                isMeteredNetwork = newState.isMetered

                val becameOnline = !prev.isOnline && newState.isOnline
                val becameUnmetered = prev.isMetered && !newState.isMetered
                val becameWifi = !prev.isWifiOrEthernet && newState.isWifiOrEthernet

                if (newState.isWifiOrEthernet) {
                    if (lanNodeInfo != null) {
                        val refreshed = withContext(Dispatchers.IO) { client.refreshLanNetwork() }
                        if (refreshed != null) {
                            lanNodeInfo = refreshed
                        }
                    } else if (syncPrefs.lanAutoStart) {
                        toggleLanP2p(true, silent = true)
                    }
                }

                if (becameOnline || becameUnmetered || becameWifi) {
                    if (localDirtyEpoch > lastSyncedEpoch || (lastSyncReport?.deferredAssets ?: 0) > 0) {
                        scheduleAutoSync(isCapture = true, forceNow = true)
                    }
                    retryPendingInboxEnrichments()
                }
            }
        }
    }

    fun applyLaunchRoute(route: LaunchRoute) {
        if (route == LaunchRoute.None) return
        pendingLaunchRoute = LaunchRoute.None
        when (route) {
            LaunchRoute.CameraCapture -> showCameraCaptureModal = true
            LaunchRoute.VoiceCapture -> showVoiceCaptureModal = true
            LaunchRoute.MicroReview -> {
                selectedTab = NavTab.Review
                startMicroReviewSession()
            }
            LaunchRoute.None -> Unit
        }
    }

    fun onExternalVaultMutated() {
        refreshInboxOnly()
        refreshCardsOnly()
        scheduleAutoSync(isCapture = true)
    }

    fun onAppResumed() {
        refreshInboxOnly()
        refreshPairedPeersOnly()
        if (localDirtyEpoch > lastSyncedEpoch) {
            scheduleAutoSync(isCapture = true, forceNow = true)
        }
    }

    fun triggerManualAutoSync() {
        scheduleAutoSync(isCapture = true, forceNow = true)
    }

    private fun scheduleAutoSync(isCapture: Boolean, forceNow: Boolean = false) {
        if (!forceNow) {
            if (isCapture && !syncPrefs.autoSyncOnCapture) return
            if (!isCapture && !syncPrefs.autoSyncOnEdit) return
        }
        localDirtyEpoch++
        if (autoSyncJob?.isActive == true) {
            if (isAutoSyncExecuting) {
                // Active pass will see localDirtyEpoch > targetEpoch and coalesce automatically
                return
            }
            if (forceNow || isCapture) {
                autoSyncJob?.cancel()
            } else {
                return
            }
        }
        val initialDelayMs = when {
            forceNow -> 0L
            isCapture -> 400L
            else -> 4_000L
        }
        autoSyncJob = scope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            runAutoSyncLoop()
        }
    }

    private suspend fun runAutoSyncLoop() {
        while (scope.isActive && lastSyncedEpoch < localDirtyEpoch) {
            val targetEpoch = localDirtyEpoch
            val hasPaired = pairedPeers.any { it.autoSync }
            val canWebDav = webDavConfig.enabled && syncPrefs.autoSyncWebdav
            if (!hasPaired && !canWebDav) {
                lastSyncedEpoch = targetEpoch
                autoSyncStatus = AutoSyncStatus(AutoSyncStateKind.Idle)
                break
            }

            flushPendingBodyImmediately()
            withContext(Dispatchers.IO) {
                runCatching { client.flushDeltas() }
            }

            val net = networkState
            if (!net.isOnline) {
                autoSyncStatus = AutoSyncStatus(AutoSyncStateKind.PendingOffline)
                break
            }
            if (!net.isWifiOrEthernet && !canWebDav) {
                autoSyncStatus = AutoSyncStatus(AutoSyncStateKind.PendingOffline)
                break
            }

            isAutoSyncExecuting = true
            autoSyncStatus = AutoSyncStatus(AutoSyncStateKind.Syncing)
            var lanSucceeded = false
            var webDavSucceeded = false
            try {
                if (net.isWifiOrEthernet && hasPaired) {
                    if (lanNodeInfo == null && syncPrefs.lanAutoStart) {
                        setLanMulticastLock(true)
                        val started = withContext(Dispatchers.IO) {
                            runCatching { client.startLanP2p(enableMdns = true) }.getOrNull()
                        }
                        if (started != null) {
                            lanNodeInfo = started
                            startLanObserver()
                        } else {
                            setLanMulticastLock(false)
                        }
                    }
                    val lanReport = withContext(Dispatchers.IO) {
                        runCatching { client.syncAllPairedLanPeers() }.getOrNull()
                    }
                    if (lanReport != null && lanReport.succeededPeers > 0) {
                        lanSucceeded = true
                        if (lanReport.totalReceivedPackets > 0 || lanReport.totalReceivedAssets > 0) {
                            syncUiVersion++
                            lastObservedSyncRevision = withContext(Dispatchers.IO) {
                                runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                            }
                            lastObservedInboxRevision = withContext(Dispatchers.IO) {
                                runCatching { client.getInboxSyncRevision() }.getOrDefault(lastObservedInboxRevision)
                            }
                            refreshAll()
                            activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                        }
                    }
                }

                val shouldRunWebDav = canWebDav && (!lanSucceeded || net.isMetered || syncPrefs.mirrorWebdavAfterLan)
                if (shouldRunWebDav) {
                    val davReport = withContext(Dispatchers.IO) {
                        runCatching { client.syncWebDavNow(isMetered = net.isMetered) }.getOrNull()
                    }
                    if (davReport != null) {
                        lastSyncReport = davReport
                        webDavSucceeded = davReport.errors.isEmpty()
                        if (davReport.downloadedDeltas > 0 || davReport.downloadedAssets > 0) {
                            syncUiVersion++
                            refreshAll()
                            activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                        }
                    }
                }
            } finally {
                isAutoSyncExecuting = false
            }

            if (lanSucceeded || webDavSucceeded) {
                lastSyncedEpoch = targetEpoch
                refreshPairedPeersOnly()
                autoSyncStatus = AutoSyncStatus(
                    kind = AutoSyncStateKind.Synced,
                    lastSyncedUtcMs = System.currentTimeMillis(),
                )
            } else {
                autoSyncStatus = AutoSyncStatus(
                    kind = if (net.isWifiOrEthernet && hasPaired && !canWebDav) {
                        AutoSyncStateKind.PendingOffline
                    } else {
                        AutoSyncStateKind.Error
                    },
                )
                break
            }
        }
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
                val (peers, paired, rev, inboxRev) = withContext(Dispatchers.IO) {
                    val p = runCatching { client.listLanPeers() }.getOrDefault(emptyList())
                    val pr = runCatching { client.listPairedPeers() }.getOrDefault(pairedPeers.toList())
                    val r = runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                    val ir = runCatching { client.getInboxSyncRevision() }.getOrDefault(lastObservedInboxRevision)
                    listOf(p, pr, r, ir)
                }.let {
                    @Suppress("UNCHECKED_CAST")
                    Quad(
                        it[0] as List<LanPeerInfoDto>,
                        it[1] as List<PairedPeerDto>,
                        it[2] as Long,
                        it[3] as Long,
                    )
                }

                lanPeers.clear()
                lanPeers.addAll(peers)
                pairedPeers.clear()
                pairedPeers.addAll(paired)

                val currentOnlinePaired = peers.filter { it.isPaired }.map { it.deviceId }.toSet()
                val newlyOnlinePaired = currentOnlinePaired - knownOnlinePairedIds
                knownOnlinePairedIds = currentOnlinePaired
                if (newlyOnlinePaired.isNotEmpty() && syncPrefs.autoSyncOnCapture) {
                    scheduleAutoSync(isCapture = true, forceNow = true)
                }

                if (rev > lastObservedSyncRevision) {
                    val revDelta = rev - lastObservedSyncRevision
                    val inboxDelta = (inboxRev - lastObservedInboxRevision).coerceAtLeast(0L)
                    lastObservedSyncRevision = rev
                    lastObservedInboxRevision = inboxRev
                    syncUiVersion = rev
                    if (inboxDelta > 0L && inboxDelta == revDelta) {
                        refreshInboxOnly()
                    } else {
                        refreshAll()
                        activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                        showToast(strings.lanIncomingSyncToast)
                    }
                }
                if (peers.any { it.relayAvailable && it.isPaired } &&
                    inboxItems.any { it.transcriptStatus == "waiting_for_lan_relay" }
                ) {
                    retryPendingInboxEnrichments()
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
            val (rev, inboxRev, prefs, paired) = withContext(Dispatchers.IO) {
                Quad(
                    runCatching { client.getSyncRevision() }.getOrDefault(0L),
                    runCatching { client.getInboxSyncRevision() }.getOrDefault(0L),
                    runCatching { client.getSyncPrefs() }.getOrDefault(LocalSyncPrefsDto()),
                    runCatching { client.listPairedPeers() }.getOrDefault(emptyList()),
                )
            }
            lastObservedSyncRevision = rev
            lastObservedInboxRevision = inboxRev
            syncPrefs = prefs
            pairedPeers.clear()
            pairedPeers.addAll(paired)

            if (checkOnboarding && isFirstLaunch) {
                showOnboarding = true
            }
            refreshAll()
            val firstId = pages.firstOrNull()?.meta?.pageId
            if (firstId != null) {
                openPage(firstId)
            }
            if (prefs.lanAutoStart && networkState.isWifiOrEthernet && lanNodeInfo == null) {
                toggleLanP2p(true, silent = true)
            }
        }
    }

    fun switchVaultDirectory(newVaultPath: String) {
        scope.launch {
            flushPendingBodyImmediately()
            lanObserverJob?.cancel()
            autoSyncJob?.cancel()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.stopLanP2p()
                    client.flushDeltas()
                    openSaltNoteClient(newVaultPath, deviceName, client.secretVault)
                }
            }.onSuccess { newClient ->
                setLanMulticastLock(false)
                lanNodeInfo = null
                lanPeers.clear()
                pairedPeers.clear()
                knownOnlinePairedIds = emptySet()
                localDirtyEpoch = 0L
                lastSyncedEpoch = 0L
                autoSyncStatus = AutoSyncStatus()
                saveLastVaultDirectory(newVaultPath)
                stopInboxAudioPlayback()
                client = newClient
                activePage = null
                selectedImageId = null
                activeCrystal = null
                microSessionPlan = null
                activeSlicePreviewCard = null
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
                val inbox = runCatching { client.listInboxItems(showArchivedInbox, 80) }.getOrDefault(emptyList())
                val prefs = runCatching { client.getSyncPrefs() }.getOrDefault(LocalSyncPrefsDto())
                val paired = runCatching { client.listPairedPeers() }.getOrDefault(emptyList())
                Triple(
                    Triple(dev, list, presets),
                    Triple(due, all, rcfg),
                    Quad(dav, inbox, prefs, paired),
                )
            }.let { (first, second, third) ->
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
                webDavConfig = third.first
                inboxItems.clear()
                inboxItems.addAll(third.second)
                syncPrefs = third.third
                pairedPeers.clear()
                pairedPeers.addAll(third.fourth)
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
            scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
                scheduleAutoSync(isCapture = false)
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
            scheduleAutoSync(isCapture = false)
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
                withContext(Dispatchers.IO) { client.syncWebDavNow(isMetered = isMeteredNetwork) }
            }.onSuccess { report ->
                lastSyncReport = report
                syncUiVersion++
                refreshAll()
                activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                val deferredSuffix = if (report.deferredAssets > 0) " · ⏳${report.deferredAssets}" else ""
                showToast("${strings.lanSyncedToast} (↑${report.uploadedDeltas} · ↓${report.downloadedDeltas}$deferredSuffix)")
            }.onFailure { err ->
                showToast("${strings.syncFailedPrefix}${err.message}")
            }
            isSyncingWebDav = false
        }
    }

    fun toggleLanP2p(enable: Boolean, silent: Boolean = false) {
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
                    lastObservedInboxRevision = withContext(Dispatchers.IO) {
                        runCatching { client.getInboxSyncRevision() }.getOrDefault(lastObservedInboxRevision)
                    }
                    refreshLanPeers()
                    refreshPairedPeersOnly()
                    startLanObserver()
                    if (!silent) {
                        showToast(strings.lanSyncStartedToast)
                    }
                }.onFailure { err ->
                    setLanMulticastLock(false)
                    if (!silent) {
                        showToast("${strings.syncFailedPrefix}${err.message}")
                    }
                }
            } else {
                lanObserverJob?.cancel()
                withContext(Dispatchers.IO) { client.stopLanP2p() }
                setLanMulticastLock(false)
                lanNodeInfo = null
                lanPeers.clear()
                knownOnlinePairedIds = emptySet()
                if (!silent) {
                    showToast(strings.lanSyncStoppedToast)
                }
            }
        }
    }

    fun refreshLanPeers() {
        scope.launch {
            val (peers, paired) = withContext(Dispatchers.IO) {
                val p = runCatching { client.listLanPeers() }.getOrDefault(emptyList())
                val pr = runCatching { client.listPairedPeers() }.getOrDefault(pairedPeers.toList())
                p to pr
            }
            lanPeers.clear()
            lanPeers.addAll(peers)
            pairedPeers.clear()
            pairedPeers.addAll(paired)
        }
    }

    fun refreshPairedPeersOnly() {
        scope.launch {
            val paired = withContext(Dispatchers.IO) {
                runCatching { client.listPairedPeers() }.getOrDefault(pairedPeers.toList())
            }
            pairedPeers.clear()
            pairedPeers.addAll(paired)
        }
    }

    fun saveSyncPrefs(prefs: LocalSyncPrefsDto) {
        syncPrefs = prefs
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.saveSyncPrefs(prefs) }
            }.onSuccess {
                if (prefs.lanAutoStart && networkState.isWifiOrEthernet && lanNodeInfo == null) {
                    toggleLanP2p(true, silent = true)
                }
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun rotateLanPairingInfo() {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching { client.rotateLanPairingInfo() }.getOrNull()
            }
            if (updated != null) {
                lanNodeInfo = updated
            }
        }
    }

    fun removePairedPeer(deviceId: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.removePairedPeer(deviceId) }
            }.onSuccess {
                refreshPairedPeersOnly()
                refreshLanPeers()
                showToast(strings.pairRemovedToast)
            }
        }
    }

    fun setPairedPeerAutoSync(deviceId: String, enabled: Boolean) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.setPairedPeerAutoSync(deviceId, enabled) }
            }.onSuccess {
                refreshPairedPeersOnly()
            }
        }
    }

    fun pairWithLanPeer(host: String, port: Int, fingerprint: String, pairingSecret: String) {
        if (isSyncingLan) return
        isSyncingLan = true
        scope.launch {
            flushPendingBodyImmediately()
            runCatching {
                withContext(Dispatchers.IO) {
                    client.flushDeltas()
                    client.pairWithLanPeer(host, port, fingerprint, pairingSecret)
                }
            }.onSuccess { res ->
                lastObservedSyncRevision = withContext(Dispatchers.IO) {
                    runCatching { client.getSyncRevision() }.getOrDefault(lastObservedSyncRevision)
                }
                lastObservedInboxRevision = withContext(Dispatchers.IO) {
                    runCatching { client.getInboxSyncRevision() }.getOrDefault(lastObservedInboxRevision)
                }
                syncUiVersion++
                refreshAll()
                refreshLanPeers()
                activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                autoSyncStatus = AutoSyncStatus(
                    kind = AutoSyncStateKind.Synced,
                    lastSyncedUtcMs = System.currentTimeMillis(),
                )
                val suffix = if (res.sentPackets + res.sentAssets + res.receivedPackets + res.receivedAssets > 0) {
                    " (↑${res.sentPackets + res.sentAssets} · ↓${res.receivedPackets + res.receivedAssets})"
                } else {
                    ""
                }
                showToast("${strings.pairSuccessToast}$suffix")
            }.onFailure { err ->
                showToast("${strings.syncFailedPrefix}${err.message}")
            }
            isSyncingLan = false
        }
    }

    fun connectFromPairingPayload(raw: String): Boolean {
        val parsed = LanPairingCodec.parse(raw)
        if (parsed == null) {
            showToast(strings.invalidPairingToast)
            return false
        }
        if (parsed.token.isNotBlank()) {
            pairWithLanPeer(parsed.host, parsed.port, parsed.fingerprint, parsed.token)
            return true
        }
        val alreadyPaired = pairedPeers.any {
            it.certFingerprint.equals(parsed.fingerprint, ignoreCase = true)
        }
        if (alreadyPaired) {
            syncWithLanPeer(parsed.host, parsed.port, parsed.fingerprint)
            return true
        }
        showToast(strings.pairTokenRequiredToast)
        return false
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
                lastObservedInboxRevision = withContext(Dispatchers.IO) {
                    runCatching { client.getInboxSyncRevision() }.getOrDefault(lastObservedInboxRevision)
                }
                syncUiVersion++
                refreshAll()
                activePage?.meta?.pageId?.let { reloadCurrentPageInPlace(it) }
                autoSyncStatus = AutoSyncStatus(
                    kind = AutoSyncStateKind.Synced,
                    lastSyncedUtcMs = System.currentTimeMillis(),
                )
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

    // ------------------------------------------------------------------------
    // Salt Inbox (盐池) & Micro-Session FSRS Actions (v0.2-alpha)
    // ------------------------------------------------------------------------

    fun refreshInboxOnly() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { client.listInboxItems(showArchivedInbox, 80) }.getOrDefault(emptyList())
            }
            inboxItems.clear()
            inboxItems.addAll(list)
        }
    }

    fun toggleShowArchivedInbox(show: Boolean) {
        showArchivedInbox = show
        refreshInboxOnly()
    }

    fun captureInboxText(text: String, isPrivate: Boolean, kind: InboxKindDto = InboxKindDto.QuickText) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.captureInboxItem(
                        InboxCaptureInputDto(
                            kind = kind,
                            textNote = cleaned,
                            isPrivate = isPrivate,
                        ),
                    )
                }
            }.onSuccess {
                refreshInboxOnly()
                showToast(strings.inboxCapturedToast)
                scheduleAutoSync(isCapture = true)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun captureInboxPhoto(
        primaryWebp: ByteArray,
        thumbWebp: ByteArray,
        isPrivate: Boolean,
        noteText: String?,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.captureInboxItem(
                        InboxCaptureInputDto(
                            kind = InboxKindDto.PhotoNote,
                            textNote = noteText?.trim()?.ifBlank { null },
                            primaryMediaBytes = primaryWebp,
                            primaryMediaExt = "webp",
                            thumbWebpBytes = thumbWebp,
                            audioDurationMs = 0,
                            isPrivate = isPrivate,
                        ),
                    )
                }
            }.onSuccess {
                refreshInboxOnly()
                showToast(strings.inboxCapturedToast)
                scheduleAutoSync(isCapture = true)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun captureInboxVoice(clip: RecordedAudioClip, isPrivate: Boolean, noteText: String?) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.captureInboxItem(
                        InboxCaptureInputDto(
                            kind = InboxKindDto.VoiceMemo,
                            textNote = noteText?.trim()?.ifBlank { null },
                            primaryMediaBytes = clip.bytes,
                            primaryMediaExt = clip.ext,
                            thumbWebpBytes = null,
                            audioDurationMs = clip.durationMs,
                            isPrivate = isPrivate,
                        ),
                    )
                }
            }.onSuccess { newId ->
                refreshInboxOnly()
                showToast(strings.inboxCapturedToast)
                scheduleAutoSync(isCapture = true)
                enrichInboxItem(newId)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun enrichInboxItem(inboxId: String) {
        if (inboxId in enrichingInboxIds) return
        enrichingInboxIds.add(inboxId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.enrichInboxItem(inboxId)
                }
            }.onSuccess {
                refreshInboxOnly()
                scheduleAutoSync(isCapture = true)
            }.onFailure {
                refreshInboxOnly()
            }
            enrichingInboxIds.remove(inboxId)
        }
    }

    fun retryPendingInboxEnrichments() {
        val candidates = inboxItems.filter {
            it.state == "unprocessed" &&
                (it.transcriptStatus == "pending" || it.transcriptStatus == "waiting_for_lan_relay")
        }
        candidates.forEach { enrichInboxItem(it.inboxId) }
    }

    fun toggleInboxItemPrivate(item: InboxItemViewDto) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.setInboxItemPrivate(item.inboxId, !item.isPrivate)
                }
            }.onSuccess {
                refreshInboxOnly()
                scheduleAutoSync(isCapture = true)
            }
        }
    }

    fun deleteInboxItem(inboxId: String) {
        if (playingAudioInboxId == inboxId) {
            stopInboxAudioPlayback()
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.deleteInboxItem(inboxId) }
            }.onSuccess {
                refreshInboxOnly()
                scheduleAutoSync(isCapture = true)
            }
        }
    }

    fun togglePlayInboxAudio(item: InboxItemViewDto) {
        val rel = item.primaryAssetPath ?: return
        if (playingAudioInboxId == item.inboxId) {
            stopInboxAudioPlayback()
            return
        }
        stopInboxAudioPlayback()
        playingAudioInboxId = item.inboxId
        activeAudioHandle = playVaultAudio(
            vaultPath = client.vaultPath,
            relPath = rel,
            onCompleted = {
                scope.launch {
                    if (playingAudioInboxId == item.inboxId) {
                        playingAudioInboxId = null
                        activeAudioHandle = null
                    }
                }
            },
            onError = { msg ->
                scope.launch {
                    playingAudioInboxId = null
                    activeAudioHandle = null
                    showToast(msg)
                }
            },
        )
    }

    fun stopInboxAudioPlayback() {
        activeAudioHandle?.stop()
        activeAudioHandle = null
        playingAudioInboxId = null
    }

    fun consumeInboxIntoActivePage(item: InboxItemViewDto) {
        val page = activePage
        if (page == null) {
            val firstId = pages.firstOrNull()?.meta?.pageId ?: return
            openPage(firstId)
            return
        }
        val pid = page.meta.pageId
        scope.launch {
            flushPendingBodyImmediately()
            var usedThumbFallback = false
            runCatching {
                if (editorMode == EditorViewMode.Canvas &&
                    item.kind == InboxKindDto.PhotoNote &&
                    (!item.primaryAssetPath.isNullOrBlank() || !item.thumbAssetPath.isNullOrBlank())
                ) {
                    val primaryBytes = item.primaryAssetPath?.takeIf { it.isNotBlank() }?.let { rel ->
                        withContext(Dispatchers.IO) { readVaultAssetBytes(client.vaultPath, rel) }
                    }
                    val thumbBytes = if (primaryBytes == null) {
                        item.thumbAssetPath?.takeIf { it.isNotBlank() }?.let { rel ->
                            withContext(Dispatchers.IO) { readVaultAssetBytes(client.vaultPath, rel) }
                        }
                    } else {
                        null
                    }
                    val rawBytes = primaryBytes ?: thumbBytes
                    if (rawBytes == null) {
                        scheduleAutoSync(isCapture = true, forceNow = true)
                        error(strings.inboxThumbFallbackToast)
                    }
                    usedThumbFallback = (primaryBytes == null)
                    val inserted = withContext(Dispatchers.IO) {
                        val node = client.insertCanvasImage(
                            pageId = pid,
                            canvasId = activeCanvasId,
                            rawImageBytes = rawBytes,
                            x = (96f - panX) / zoom,
                            y = (96f - panY) / zoom,
                            maxWidth = 360f,
                        )
                        client.consumeInboxIntoPage(item.inboxId, pid, node.imageId)
                        node
                    }
                    selectedImageId = inserted.imageId
                    reloadCurrentPageInPlace(pid)
                } else {
                    val blockId = "inbx_blk_${System.currentTimeMillis()}"
                    val blockMd = buildInboxMarkdownInsert(item)
                    val updatedMd = if (editableBody.isBlank()) {
                        blockMd
                    } else {
                        "${editableBody.trimEnd()}\n\n$blockMd\n"
                    }
                    withContext(Dispatchers.IO) {
                        client.updatePageBody(pid, updatedMd)
                        client.consumeInboxIntoPage(item.inboxId, pid, blockId)
                    }
                    editableBody = updatedMd
                    reloadCurrentPageInPlace(pid)
                }
            }.onSuccess {
                refreshInboxOnly()
                refreshPageListOnly()
                showToast(if (usedThumbFallback) strings.inboxThumbFallbackToast else strings.inboxConsumedToast)
                scheduleAutoSync(isCapture = true)
            }.onFailure { err ->
                showToast("${strings.saveFailedPrefix}${err.message}")
            }
        }
    }

    fun triggerInboxDirectHalo(item: InboxItemViewDto, presetId: String) {
        val pageId = activePage?.meta?.pageId ?: pages.firstOrNull()?.meta?.pageId ?: return
        cancelActiveCrystalStream()
        val preset = haloPresets.firstOrNull { it.presetId == presetId }
        activeCrystal = ActiveCrystalBubble(
            presetId = presetId,
            presetName = preset?.name ?: presetId,
            accentHex = preset?.colorHex ?: "#8D99AE",
            isStreaming = true,
        )
        val listener = buildHaloStreamListener(pageId, presetId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.triggerInboxHalo(
                        inboxId = item.inboxId,
                        pageId = pageId,
                        presetId = presetId,
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
                    errorMessage = if (isPrivacy) strings.privateBlockError else (err.message ?: strings.requestFailed),
                )
            }
        }
    }

    private fun buildInboxMarkdownInsert(item: InboxItemViewDto): String = buildString {
        when (item.kind) {
            InboxKindDto.PhotoNote -> {
                val imgPath = item.primaryAssetPath ?: item.thumbAssetPath
                if (!imgPath.isNullOrBlank()) {
                    append("![${item.textNote ?: "Salt Inbox"}]($imgPath)\n")
                }
                item.textNote?.takeIf { it.isNotBlank() }?.let { append("> $it\n") }
                item.transcriptText?.takeIf { it.isNotBlank() }?.let {
                    append("\n> [!salt-mineral] ${strings.calloutMineralTitle}\n> ${it.replace("\n", "\n> ")}\n")
                }
            }
            InboxKindDto.VoiceMemo -> {
                val secs = (item.audioDurationMs / 1000).coerceAtLeast(1)
                append("> [!note] 🎙️ ${strings.captureVoice} (${secs}s)\n")
                item.summaryText?.takeIf { it.isNotBlank() }?.let {
                    append("> **${it}**\n>\n")
                }
                item.transcriptText?.takeIf { it.isNotBlank() }?.let {
                    append("> ${it.replace("\n", "\n> ")}\n")
                } ?: item.textNote?.takeIf { it.isNotBlank() }?.let {
                    append("> $it\n")
                }
            }
            InboxKindDto.QuickText, InboxKindDto.WebClip -> {
                val txt = item.textNote ?: item.summaryText ?: ""
                append("> $txt\n")
            }
        }
    }.trimEnd()

    fun startMicroReviewSession(
        budgetSeconds: Int = microSessionBudgetSecs,
        energyLevel: Int = microSessionEnergyLevel,
        rescueMode: Boolean = microSessionRescueMode,
    ) {
        microSessionBudgetSecs = budgetSeconds
        microSessionEnergyLevel = energyLevel
        microSessionRescueMode = rescueMode
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.buildMicroReviewSession(budgetSeconds, energyLevel, rescueMode)
                }
            }.onSuccess { plan ->
                microSessionPlan = plan
                microSessionIndex = 0
                isMicroSessionFinished = plan.cards.isEmpty()
                currentCardRevealed = false
                cardShownAtMs = System.currentTimeMillis()
            }.onFailure { err ->
                showToast("${strings.requestFailed}: ${err.message}")
            }
        }
    }

    fun submitMicroCardRating(card: ReviewCardViewDto, rating: Int) {
        val elapsedMs = (System.currentTimeMillis() - cardShownAtMs).coerceIn(500L, 600_000L).toInt()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client.submitCardReview(card.pageId, card.cardId, rating, elapsedMs) }
            }
            currentCardRevealed = false
            cardShownAtMs = System.currentTimeMillis()
            val plan = microSessionPlan
            if (plan != null && microSessionIndex + 1 < plan.cards.size) {
                microSessionIndex += 1
            } else {
                isMicroSessionFinished = true
            }
            refreshCardsOnly()
            scheduleAutoSync(isCapture = false)
        }
    }

    fun jumpToCardAnchor(card: ReviewCardViewDto) {
        activeSlicePreviewCard = null
        selectedTab = NavTab.Notes
        openPage(card.pageId, targetHaloId = card.anchorHaloId)
    }

    fun dispose() {
        stopInboxAudioPlayback()
        bodySaveJob?.cancel()
        periodicFlushJob?.cancel()
        lanObserverJob?.cancel()
        autoSyncJob?.cancel()
        networkObserverDispose?.invoke()
        networkObserverDispose = null
        setLanMulticastLock(false)
        runCatching { client.stopLanP2p() }
    }
}
