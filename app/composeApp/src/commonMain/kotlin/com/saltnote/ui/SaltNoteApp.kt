package com.saltnote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.i18n.SaltStrings
import com.saltnote.ui.platform.pickVaultDirectory
import com.saltnote.ui.screens.FsrsReviewScreen
import com.saltnote.ui.screens.NotesWorkspaceScreen
import com.saltnote.ui.screens.SettingsScreen
import com.saltnote.ui.screens.SyncScreen
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import com.saltnote.ui.theme.SaltNoteTheme

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
                val isWideScreen = maxWidth >= 840.dp

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!isWideScreen) {
                            NavigationBar {
                                NavTab.entries.forEach { tab ->
                                    val label = navTabLabel(tab, strings)
                                    NavigationBarItem(
                                        selected = state.selectedTab == tab,
                                        onClick = { state.selectedTab = tab },
                                        icon = { NavTabIcon(tab, state.dueCards.size, label) },
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
                                NavTab.entries.forEach { tab ->
                                    val label = navTabLabel(tab, strings)
                                    NavigationRailItem(
                                        selected = state.selectedTab == tab,
                                        onClick = { state.selectedTab = tab },
                                        icon = { NavTabIcon(tab, state.dueCards.size, label) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            VerticalDivider()
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            when (state.selectedTab) {
                                NavTab.Notes -> NotesWorkspaceScreen(state = state, isWideScreen = isWideScreen)
                                NavTab.Review -> FsrsReviewScreen(state = state)
                                NavTab.Sync -> SyncScreen(state = state)
                                NavTab.Settings -> SettingsScreen(state = state)
                            }
                        }
                    }
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

private fun navTabLabel(tab: NavTab, strings: SaltStrings): String = when (tab) {
    NavTab.Notes -> strings.navNotes
    NavTab.Review -> strings.navReview
    NavTab.Sync -> strings.navSync
    NavTab.Settings -> strings.navSettings
}

@Composable
private fun NavTabIcon(tab: NavTab, dueCount: Int, description: String) {
    val icon: ImageVector = when (tab) {
        NavTab.Notes -> Icons.Rounded.Description
        NavTab.Review -> Icons.Rounded.Style
        NavTab.Sync -> Icons.Rounded.CloudSync
        NavTab.Settings -> Icons.Rounded.Settings
    }
    if (tab == NavTab.Review && dueCount > 0) {
        BadgedBox(badge = { Badge { Text(dueCount.toString()) } }) {
            Icon(icon, contentDescription = description)
        }
    } else {
        Icon(icon, contentDescription = description)
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
