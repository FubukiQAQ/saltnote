package com.saltnote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.FsrsCardStateDto
import com.saltnote.bridge.InboxItemViewDto
import com.saltnote.bridge.InboxKindDto
import com.saltnote.bridge.PageSummaryDto
import com.saltnote.ui.components.SaltRichInlineText
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.currentHourOfDay
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeHomeScreen(
    state: SaltAppState,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    var showNewPageDialog by remember { mutableStateOf(false) }

    val hour = remember { currentHourOfDay() }
    val greeting = when {
        hour < 12 -> strings.welcomeGreetingMorning
        hour < 18 -> strings.welcomeGreetingAfternoon
        else -> strings.welcomeGreetingEvening
    }

    val startMicroReview = {
        state.selectedTab = NavTab.Review
        state.startMicroReviewSession(budgetSeconds = state.microSessionBudgetSecs)
    }

    if (isWideScreen) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val useTwoColumns = maxWidth >= 900.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Wide Greeting Header + Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = strings.welcomeSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { showNewPageDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.newNote)
                        }
                        FilledTonalButton(onClick = { state.selectedTab = NavTab.Notes }) {
                            Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.openWorkspace)
                        }
                        if (state.dueCards.isNotEmpty()) {
                            OutlinedButton(onClick = startMicroReview) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("${strings.hubReadyStart} (${state.dueCards.size})")
                            }
                        }
                    }
                }

                if (useTwoColumns) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            RecentNotesDashboardSection(
                                state = state,
                                onViewAll = { state.selectedTab = NavTab.Notes },
                            )
                            NewInboxDashboardSection(
                                state = state,
                                onViewAll = {
                                    state.showSaltTray = true
                                    state.selectedTab = NavTab.Notes
                                },
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.85f),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            RecentCardsDashboardSection(
                                state = state,
                                maxItems = 5,
                                onStartReview = startMicroReview,
                                onViewAll = { state.selectedTab = NavTab.Review },
                            )
                        }
                    }
                } else {
                    RecentNotesDashboardSection(
                        state = state,
                        onViewAll = { state.selectedTab = NavTab.Notes },
                    )
                    RecentCardsDashboardSection(
                        state = state,
                        maxItems = 5,
                        onStartReview = startMicroReview,
                        onViewAll = { state.selectedTab = NavTab.Review },
                    )
                    NewInboxDashboardSection(
                        state = state,
                        onViewAll = {
                            state.showSaltTray = true
                            state.selectedTab = NavTab.Notes
                        },
                    )
                }
            }
        }
    } else {
        // Phone / Compact: Greeting + Quick Capture Grid + Recent Flashcards
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.welcomeSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Quick Capture 2x2 Action Grid
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.quickCaptureTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickCaptureTile(
                        icon = Icons.Rounded.PhotoCamera,
                        label = strings.capturePhoto,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { state.showCameraCaptureModal = true },
                    )
                    QuickCaptureTile(
                        icon = Icons.Rounded.Mic,
                        label = strings.captureVoice,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { state.showVoiceCaptureModal = true },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickCaptureTile(
                        icon = Icons.Rounded.Edit,
                        label = strings.captureText,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { state.showQuickTextCaptureModal = true },
                    )
                    QuickCaptureTile(
                        icon = Icons.AutoMirrored.Rounded.NoteAdd,
                        label = strings.newNote,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        onClick = { showNewPageDialog = true },
                    )
                }
            }

            RecentCardsDashboardSection(
                state = state,
                maxItems = 6,
                onStartReview = startMicroReview,
                onViewAll = { state.selectedTab = NavTab.Review },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showNewPageDialog) {
        NewPageDialog(
            onDismiss = { showNewPageDialog = false },
            onCreate = { title, mode, isPrivate, tags ->
                showNewPageDialog = false
                state.createNewPage(title, mode, isPrivate, tags, switchToNotesTab = true)
            },
        )
    }
}

@Composable
private fun QuickCaptureTile(
    icon: ImageVector,
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentNotesDashboardSection(
    state: SaltAppState,
    onViewAll: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val recentPages = remember(state.pages.toList(), state.uiPrefs.recentPages) {
        state.recentPagesForHome(6)
    }

    SectionCardContainer(
        icon = Icons.Rounded.Description,
        title = strings.recentNotesTitle,
        actionLabel = strings.viewAllNotes,
        onAction = onViewAll,
    ) {
        if (recentPages.isEmpty()) {
            EmptySectionHint(text = strings.recentNotesEmpty)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentPages.forEach { page ->
                    RecentNoteRowCard(
                        page = page,
                        onClick = {
                            state.openPage(pageId = page.meta.pageId, switchToNotesTab = true)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentNoteRowCard(
    page: PageSummaryDto,
    onClick: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val isJournal = page.meta.mode == "journal"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isJournal) Icons.Rounded.Brush else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = page.meta.title.ifBlank { if (isJournal) strings.journalCanvas else strings.studyNote },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (page.meta.isPrivate) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = strings.privateLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (page.canvases.size > 1) {
                    MiniCountPill(label = "${page.canvases.size}C")
                }
                if (page.documents.size > 1) {
                    MiniCountPill(label = "${page.documents.size}D")
                }
                Text(
                    text = formatUtcTimestamp(page.meta.updatedAtUtc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (page.snippet.isNotBlank()) {
                Text(
                    text = page.snippet.replace('\n', ' ').trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentCardsDashboardSection(
    state: SaltAppState,
    maxItems: Int,
    onStartReview: () -> Unit,
    onViewAll: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val cards = remember(state.dueCards.toList(), state.allCards.toList(), state.uiPrefs.recentCards) {
        state.recentCardsForHome(maxItems)
    }
    val pageTitles = remember(state.pages.toList()) {
        state.pages.associate { it.meta.pageId to it.meta.title }
    }

    SectionCardContainer(
        icon = Icons.Rounded.Style,
        title = strings.recentCardsTitle,
        badgeText = "${state.dueCards.size} ${strings.dueTodayLabel}",
        actionLabel = strings.viewAllCards,
        onAction = onViewAll,
    ) {
        if (cards.isEmpty()) {
            EmptySectionHint(text = strings.recentCardsEmpty)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cards.forEach { card ->
                    HomeFlipCardItem(
                        card = card,
                        pageTitle = pageTitles[card.pageId].orEmpty().ifBlank { strings.studyNote },
                        onFlipped = { state.recordCardVisit(card.cardId, card.pageId) },
                    )
                }
                if (state.dueCards.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onStartReview,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${strings.hubReadyStart} (${state.dueCards.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFlipCardItem(
    card: FsrsCardStateDto,
    pageTitle: String,
    onFlipped: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var revealed by remember(card.cardId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                revealed = !revealed
                onFlipped()
            },
        shape = RoundedCornerShape(12.dp),
        color = if (revealed) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Flip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = strings.flipCardHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SaltRichInlineText(
                input = card.front,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                codeBg = MaterialTheme.colorScheme.surfaceContainerHighest,
                codeFg = MaterialTheme.colorScheme.onSurface,
                linkColor = MaterialTheme.colorScheme.primary,
            )

            if (revealed) {
                Spacer(Modifier.height(2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    SaltRichInlineText(
                        input = card.back,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        codeBg = MaterialTheme.colorScheme.surfaceContainerHighest,
                        codeFg = MaterialTheme.colorScheme.onSurface,
                        linkColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewInboxDashboardSection(
    state: SaltAppState,
    onViewAll: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val unprocessed = remember(state.inboxItems.toList()) {
        state.inboxItems.filter { it.state == "unprocessed" }.take(6)
    }

    SectionCardContainer(
        icon = Icons.Rounded.Inbox,
        title = strings.newInboxTitle,
        badgeText = if (unprocessed.isNotEmpty()) "${unprocessed.size}" else null,
        actionLabel = strings.viewAllInbox,
        onAction = onViewAll,
    ) {
        if (unprocessed.isEmpty()) {
            EmptySectionHint(text = strings.newInboxEmpty)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                unprocessed.forEach { item ->
                    HomeInboxItemRow(
                        item = item,
                        vaultPath = state.client.vaultPath,
                        syncVersion = state.syncUiVersion,
                        onOpenPhoto = { rel ->
                            state.lightboxImageRequest = rel to item.textNote
                        },
                        onConsumeIntoNote = {
                            state.consumeInboxIntoRecentOrActivePage(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeInboxItemRow(
    item: InboxItemViewDto,
    vaultPath: String,
    syncVersion: Long,
    onOpenPhoto: (String) -> Unit,
    onConsumeIntoNote: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val relPath = item.thumbAssetPath ?: item.primaryAssetPath
    val previewBmp by produceState<ImageBitmap?>(null, vaultPath, relPath, syncVersion) {
        value = if (item.kind == InboxKindDto.PhotoNote && !relPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) { loadImageBitmapFromVault(vaultPath, relPath) }
        } else {
            null
        }
    }

    val titleText = when (item.kind) {
        InboxKindDto.PhotoNote -> item.textNote?.takeIf { it.isNotBlank() }
            ?: item.transcriptText?.takeIf { it.isNotBlank() }
            ?: strings.capturePhoto
        InboxKindDto.VoiceMemo -> {
            val secs = (item.audioDurationMs / 1000).coerceAtLeast(1)
            val snippet = item.summaryText?.takeIf { it.isNotBlank() }
                ?: item.transcriptText?.takeIf { it.isNotBlank() }
                ?: item.textNote.orEmpty()
            if (snippet.isNotBlank()) "${secs}s · $snippet" else "${strings.captureVoice} (${secs}s)"
        }
        InboxKindDto.QuickText, InboxKindDto.WebClip ->
            item.textNote?.takeIf { it.isNotBlank() }
                ?: item.summaryText?.takeIf { it.isNotBlank() }
                ?: strings.captureText
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.kind == InboxKindDto.PhotoNote && previewBmp != null) {
                val clickPath = item.primaryAssetPath ?: relPath
                Image(
                    bitmap = previewBmp!!,
                    contentDescription = item.textNote,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (!clickPath.isNullOrBlank()) onOpenPhoto(clickPath)
                        },
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when (item.kind) {
                            InboxKindDto.PhotoNote -> Icons.Rounded.Image
                            InboxKindDto.VoiceMemo -> Icons.Rounded.Mic
                            InboxKindDto.QuickText, InboxKindDto.WebClip -> Icons.Rounded.Edit
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatUtcTimestamp(item.createdAtUtc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(onClick = onConsumeIntoNote) {
                Text(strings.consumeIntoRecentNote, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SectionCardContainer(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    actionLabel: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
            content()
        }
    }
}

@Composable
private fun EmptySectionHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniCountPill(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
