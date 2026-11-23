package com.saltnote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.i18n.SaltStrings
import com.saltnote.ui.platform.PlatformInboxCameraCaptureDialog
import com.saltnote.ui.platform.pickVaultDirectory
import com.saltnote.ui.screens.FragmentHubScreen
import com.saltnote.ui.screens.FsrsReviewScreen
import com.saltnote.ui.screens.NotesWorkspaceScreen
import com.saltnote.ui.screens.QuickTextCaptureDialog
import com.saltnote.ui.screens.QuickVoiceCaptureDialog
import com.saltnote.ui.screens.SettingsScreen
import com.saltnote.ui.screens.SyncScreen
import com.saltnote.ui.state.LaunchRoute
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import com.saltnote.ui.theme.SaltNoteTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaltNoteApp(state: SaltAppState) {
    val strings = state.strings
    CompositionLocalProvider(LocalSaltStrings provides strings) {
        SaltNoteTheme(darkTheme = state.darkTheme) {
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(state.snackbarMessage) {
                val msg = state.snackbarMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    state.clearToast()
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Material 3 WindowSizeClass breakpoint: Compact < 600dp, Medium/Expanded >= 600dp
                val isCompact = maxWidth < 600.dp
                val isWideScreen = !isCompact
                val unprocessedInboxCount = state.inboxItems.count { it.state == "unprocessed" }

                LaunchedEffect(isCompact) {
                    if (!state.initialTabResolved) {
                        state.selectedTab = if (isCompact) NavTab.Hub else NavTab.Notes
                        state.initialTabResolved = true
                    } else if (isWideScreen && state.selectedTab == NavTab.Hub) {
                        state.selectedTab = NavTab.Notes
                    }
                }

                LaunchedEffect(state.pendingLaunchRoute) {
                    if (state.pendingLaunchRoute != LaunchRoute.None) {
                        state.applyLaunchRoute(state.pendingLaunchRoute)
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (isCompact) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = when (state.selectedTab) {
                                            NavTab.Hub -> strings.hubTitle
                                            NavTab.Review -> strings.reviewTitle
                                            NavTab.Notes -> strings.navNotes
                                            NavTab.Sync, NavTab.Settings -> "SaltNote"
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                },
                                actions = {
                                    IconButton(onClick = { state.showCompactSyncModal = true }) {
                                        val deferredCount = state.lastSyncReport?.deferredAssets ?: 0
                                        if (state.isMeteredNetwork || deferredCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge {
                                                        if (deferredCount > 0) {
                                                            Text(deferredCount.toString())
                                                        }
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = if (state.isMeteredNetwork) {
                                                        Icons.Rounded.SignalCellularAlt
                                                    } else {
                                                        Icons.Rounded.CloudSync
                                                    },
                                                    contentDescription = strings.navSync,
                                                )
                                            }
                                        } else {
                                            Icon(Icons.Rounded.CloudSync, contentDescription = strings.navSync)
                                        }
                                    }
                                    IconButton(onClick = { state.showCompactSettingsModal = true }) {
                                        Icon(Icons.Rounded.Settings, contentDescription = strings.navSettings)
                                    }
                                },
                            )
                        }
                    },
                    bottomBar = {
                        if (isCompact) {
                            val compactTabs = listOf(NavTab.Hub, NavTab.Review, NavTab.Notes)
                            NavigationBar {
                                compactTabs.forEach { tab ->
                                    val label = navTabLabel(tab, strings)
                                    NavigationBarItem(
                                        selected = state.selectedTab == tab,
                                        onClick = { state.selectedTab = tab },
                                        icon = {
                                            NavTabIcon(
                                                tab = tab,
                                                dueCount = state.dueCards.size,
                                                inboxUnprocessedCount = unprocessedInboxCount,
                                                isCompact = true,
                                                description = label,
                                            )
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        if (isWideScreen) {
                            val wideTabs = listOf(NavTab.Notes, NavTab.Review, NavTab.Sync, NavTab.Settings)
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                header = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 12.dp),
                                    ) {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(44.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Rounded.AutoStories,
                                                    contentDescription = "SaltNote",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("SaltNote", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                            ) {
                                wideTabs.forEach { tab ->
                                    val label = navTabLabel(tab, strings)
                                    NavigationRailItem(
                                        selected = state.selectedTab == tab,
                                        onClick = { state.selectedTab = tab },
                                        icon = {
                                            NavTabIcon(
                                                tab = tab,
                                                dueCount = state.dueCards.size,
                                                inboxUnprocessedCount = unprocessedInboxCount,
                                                isCompact = false,
                                                description = label,
                                            )
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            VerticalDivider()
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (isCompact) {
                                when (state.selectedTab) {
                                    NavTab.Hub, NavTab.Sync, NavTab.Settings -> FragmentHubScreen(state = state)
                                    NavTab.Review -> FsrsReviewScreen(state = state, isCompact = true)
                                    NavTab.Notes -> NotesWorkspaceScreen(state = state, isWideScreen = false)
                                }
                            } else {
                                when (state.selectedTab) {
                                    NavTab.Hub, NavTab.Notes -> NotesWorkspaceScreen(state = state, isWideScreen = true)
                                    NavTab.Review -> FsrsReviewScreen(state = state, isCompact = false)
                                    NavTab.Sync -> SyncScreen(state = state)
                                    NavTab.Settings -> SettingsScreen(state = state)
                                }
                            }

                            if (isCompact && state.selectedTab != NavTab.Review) {
                                FloatingCaptureCapsule(
                                    onPhoto = { state.showCameraCaptureModal = true },
                                    onVoice = { state.showVoiceCaptureModal = true },
                                    onQuickText = { state.showQuickTextCaptureModal = true },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 14.dp),
                                )
                            }
                        }
                    }
                }

                if (state.showCompactSyncModal) {
                    CompactModalSheetDialog(
                        title = strings.syncTitle,
                        onDismiss = { state.showCompactSyncModal = false },
                    ) {
                        SyncScreen(state = state)
                    }
                }

                if (state.showCompactSettingsModal) {
                    CompactModalSheetDialog(
                        title = strings.settingsTitle,
                        onDismiss = { state.showCompactSettingsModal = false },
                    ) {
                        SettingsScreen(state = state)
                    }
                }

                if (state.showCameraCaptureModal) {
                    PlatformInboxCameraCaptureDialog(
                        onCaptured = { primaryWebp, thumbWebp, isPrivate, noteText ->
                            state.showCameraCaptureModal = false
                            state.captureInboxPhoto(primaryWebp, thumbWebp, isPrivate, noteText)
                        },
                        onDismiss = { state.showCameraCaptureModal = false },
                    )
                }

                if (state.showVoiceCaptureModal) {
                    QuickVoiceCaptureDialog(
                        onCaptured = { clip, isPrivate, noteText ->
                            state.showVoiceCaptureModal = false
                            state.captureInboxVoice(clip, isPrivate, noteText)
                        },
                        onDismiss = { state.showVoiceCaptureModal = false },
                    )
                }

                if (state.showQuickTextCaptureModal) {
                    QuickTextCaptureDialog(
                        onCaptured = { text, isPrivate ->
                            state.showQuickTextCaptureModal = false
                            state.captureInboxText(text, isPrivate)
                        },
                        onDismiss = { state.showQuickTextCaptureModal = false },
                    )
                }

                if (state.showOnboarding) {
                    ZeroConfigOnboardingDialog(
                        vaultPath = state.client.vaultPath,
                        onStart = { state.showOnboarding = false },
                        onChangeVault = {
                            pickVaultDirectory(
                                initialPath = state.client.vaultPath,
                                strings = strings,
                                onPicked = { picked ->
                                    state.switchVaultDirectory(picked)
                                    state.showOnboarding = false
                                },
                                onMessage = { msg -> state.notifyMessage(msg) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingCaptureCapsule(
    onPhoto: () -> Unit,
    onVoice: () -> Unit,
    onQuickText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPhoto) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = strings.capturePhoto,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onVoice) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = strings.captureVoice,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onQuickText) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = strings.captureText,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactModalSheetDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val strings = LocalSaltStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = strings.close)
                        }
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

private fun navTabLabel(tab: NavTab, strings: SaltStrings): String = when (tab) {
    NavTab.Hub -> strings.navInbox
    NavTab.Notes -> strings.navNotes
    NavTab.Review -> strings.navReview
    NavTab.Sync -> strings.navSync
    NavTab.Settings -> strings.navSettings
}

@Composable
private fun NavTabIcon(
    tab: NavTab,
    dueCount: Int,
    inboxUnprocessedCount: Int,
    isCompact: Boolean,
    description: String,
) {
    val icon: ImageVector = when (tab) {
        NavTab.Hub -> Icons.Rounded.Inbox
        NavTab.Notes -> Icons.Rounded.Description
        NavTab.Review -> Icons.Rounded.Style
        NavTab.Sync -> Icons.Rounded.CloudSync
        NavTab.Settings -> Icons.Rounded.Settings
    }
    when {
        tab == NavTab.Hub && inboxUnprocessedCount > 0 -> {
            BadgedBox(badge = { Badge { Text(inboxUnprocessedCount.toString()) } }) {
                Icon(icon, contentDescription = description)
            }
        }
        tab == NavTab.Review && dueCount > 0 -> {
            BadgedBox(
                badge = {
                    if (isCompact) {
                        // Gentle dot without intimidating large overdue numbers
                        Badge()
                    } else {
                        Badge { Text(dueCount.toString()) }
                    }
                },
            ) {
                Icon(icon, contentDescription = description)
            }
        }
        else -> Icon(icon, contentDescription = description)
    }
}

@Composable
private fun ZeroConfigOnboardingDialog(
    vaultPath: String,
    onStart: () -> Unit,
    onChangeVault: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    AlertDialog(
        onDismissRequest = onStart,
        icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
        title = { Text(strings.onboardingTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.onboardingSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = vaultPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text(strings.startUsing)
            }
        },
        dismissButton = {
            TextButton(onClick = onChangeVault) {
                Text(strings.changeDirectory)
            }
        },
    )
}
