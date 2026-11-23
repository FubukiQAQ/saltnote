package com.saltnote.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saltnote.bridge.FsrsCardStateDto
import com.saltnote.bridge.ReviewCardViewDto
import com.saltnote.ui.components.MarkdownContent
import com.saltnote.ui.components.SaltAssistChip
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun FsrsReviewScreen(
    state: SaltAppState,
    isCompact: Boolean = false,
) {
    val strings = LocalSaltStrings.current
    var showAllCards by remember { mutableStateOf(false) }
    var showCreateCardDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<FsrsCardStateDto?>(null) }
    var deletingCard by remember { mutableStateOf<FsrsCardStateDto?>(null) }

    LaunchedEffect(state.microSessionPlan, state.dueCards.size) {
        if (state.microSessionPlan == null && !state.isMicroSessionFinished) {
            state.startMicroReviewSession()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isCompact) 14.dp else 20.dp, vertical = if (isCompact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 14.dp),
    ) {
        // Header row: Title + mode switcher + New Card
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.reviewTitle,
                style = if (isCompact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            )
            FilledTonalButton(
                onClick = { showCreateCardDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.newCard)
            }
        }

        // Mode & Micro-Session controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SaltFilterChip(
                selected = !showAllCards,
                onClick = {
                    showAllCards = false
                    if (state.microSessionPlan == null && !state.isMicroSessionFinished) {
                        state.startMicroReviewSession()
                    }
                },
                label = { Text(strings.hubMicroReviewTitle) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp)) },
            )
            SaltFilterChip(
                selected = showAllCards,
                onClick = { showAllCards = true },
                label = { Text("${strings.allCardsLabel} (${state.allCards.size})") },
                leadingIcon = { Icon(Icons.Rounded.Style, null, modifier = Modifier.size(16.dp)) },
            )

            if (!showAllCards) {
                listOf(180 to "3m", 300 to "5m", 600 to "10m").forEach { (secs, tag) ->
                    SaltFilterChip(
                        selected = state.microSessionBudgetSecs == secs,
                        onClick = { state.startMicroReviewSession(budgetSeconds = secs) },
                        label = { Text(tag) },
                    )
                }
                SaltFilterChip(
                    selected = state.microSessionRescueMode,
                    onClick = { state.startMicroReviewSession(rescueMode = !state.microSessionRescueMode) },
                    label = { Text(strings.microSessionRescueMode) },
                    leadingIcon = { Icon(Icons.Rounded.Favorite, null, modifier = Modifier.size(15.dp)) },
                )
                SaltFilterChip(
                    selected = state.geekSwipeEnabled,
                    onClick = { state.geekSwipeEnabled = !state.geekSwipeEnabled },
                    label = { Text(strings.microSessionGeekSwipe) },
                    leadingIcon = { Icon(Icons.Rounded.Gesture, null, modifier = Modifier.size(15.dp)) },
                )
            }
        }

        if (!showAllCards) {
            MicroReviewWorkspace(
                state = state,
                isCompact = isCompact,
                onEditCard = { card -> editingCard = card.toFsrsCardStateDto() },
                onDeleteCard = { card -> deletingCard = card.toFsrsCardStateDto() },
                onOpenAllCards = { showAllCards = true },
                onStopToday = {
                    state.microSessionPlan = null
                    state.isMicroSessionFinished = false
                    state.selectedTab = if (isCompact) NavTab.Hub else NavTab.Notes
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            AllCardsManagerView(
                state = state,
                onEditCard = { editingCard = it },
                onDeleteCard = { deletingCard = it },
                modifier = Modifier.weight(1f),
            )
        }
    }

    state.activeSlicePreviewCard?.let { sliceCard ->
        SlicePreviewDialog(
            vaultPath = state.client.vaultPath,
            card = sliceCard,
            onDismiss = { state.activeSlicePreviewCard = null },
            onEnterFullPage = { state.jumpToCardAnchor(sliceCard) },
        )
    }

    if (showCreateCardDialog) {
        FlashcardEditorDialog(
            title = strings.newCard,
            initialFront = "",
            initialBack = "",
            onDismiss = { showCreateCardDialog = false },
            onSave = { f, b ->
                state.createManualFlashcard(f, b)
                showCreateCardDialog = false
            },
        )
    }

    editingCard?.let { card ->
        FlashcardEditorDialog(
            title = strings.editCard,
            initialFront = card.front,
            initialBack = card.back,
            onDismiss = { editingCard = null },
            onSave = { f, b ->
                state.updateFlashcard(card.pageId, card.cardId, f, b)
                editingCard = null
            },
        )
    }

    deletingCard?.let { card ->
        AlertDialog(
            onDismissRequest = { deletingCard = null },
            title = { Text(strings.deleteCardTitle) },
            text = { Text(strings.deleteCardConfirm) },
            confirmButton = {
                Button(
                    onClick = {
                        state.deleteFlashcard(card.pageId, card.cardId)
                        deletingCard = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(strings.confirmDelete) }
            },
            dismissButton = {
                TextButton(onClick = { deletingCard = null }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun MicroReviewWorkspace(
    state: SaltAppState,
    isCompact: Boolean,
    onEditCard: (ReviewCardViewDto) -> Unit,
    onDeleteCard: (ReviewCardViewDto) -> Unit,
    onOpenAllCards: () -> Unit,
    onStopToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val plan = state.microSessionPlan
    val currentCard = if (!state.isMicroSessionFinished) {
        plan?.cards?.getOrNull(state.microSessionIndex)
    } else {
        null
    }

    if (plan == null) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
        }
        return
    }

    if (currentCard == null) {
        // Empty due queue or celebratory micro-session completion
        val hasAnyCardsDue = state.dueCards.isNotEmpty()
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        text = if (plan.cards.isEmpty() && !hasAnyCardsDue) {
                            strings.hubNoCardsDue
                        } else {
                            strings.microSessionCompleteTitle
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (plan.cards.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "${plan.cards.size} / ${plan.cards.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (hasAnyCardsDue) {
                            Button(
                                onClick = { state.startMicroReviewSession(budgetSeconds = 180) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(strings.microSessionAnotherRound)
                            }
                        } else {
                            Button(
                                onClick = onOpenAllCards,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(strings.allCardsLabel)
                            }
                        }
                        FilledTonalButton(
                            onClick = onStopToday,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(strings.microSessionStopToday)
                        }
                    }
                }
            }
        }
        return
    }

    // Active micro-session: soft progress bar (no shaming overdue total) + 65%/35% thumb dock
    val totalCards = plan.cards.size.coerceAtLeast(1)
    val doneCards = state.microSessionIndex.coerceIn(0, totalCards)
    val remainingFraction = (totalCards - doneCards).toFloat() / totalCards.toFloat()
    val estMinutesLeft = ceil((plan.estimatedSeconds * remainingFraction) / 60.0)
        .toInt()
        .coerceAtLeast(1)
    val progress = (doneCards + 1).toFloat() / totalCards.toFloat()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Soft Session Progress Bar
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${doneCards + 1} / $totalCards · ${strings.microSessionRemainingMinPrefix}$estMinutesLeft${strings.microSessionRemainingMinSuffix}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "R ${(currentCard.retrievability * 100.0).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }

        // Upper ~65%: Interactive Swipeable / Tap-to-Flip Card Area
        var dragX by remember(currentCard.cardId) { mutableStateOf(0f) }
        var dragY by remember(currentCard.cardId) { mutableStateOf(0f) }
        val animX by animateFloatAsState(dragX)
        val animY by animateFloatAsState(dragY)

        val pendingSwipeRating = remember(dragX, dragY, state.currentCardRevealed, state.geekSwipeEnabled) {
            if (!state.geekSwipeEnabled || !state.currentCardRevealed) {
                null
            } else if (dragY < -95f && abs(dragY) > abs(dragX)) {
                4 // Easy
            } else if (dragX > 110f) {
                3 // Good
            } else if (dragX < -110f) {
                1 // Again
            } else {
                null
            }
        }

        Box(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .offset { IntOffset(animX.roundToInt(), animY.roundToInt()) }
                    .graphicsLayer {
                        rotationZ = (animX / 45f).coerceIn(-12f, 12f)
                    }
                    .pointerInput(currentCard.cardId, state.currentCardRevealed, state.geekSwipeEnabled) {
                        if (!state.geekSwipeEnabled || !state.currentCardRevealed) return@pointerInput
                        detectDragGestures(
                            onDragEnd = {
                                val rating = when {
                                    dragY < -95f && abs(dragY) > abs(dragX) -> 4
                                    dragX > 110f -> 3
                                    dragX < -110f -> 1
                                    else -> null
                                }
                                dragX = 0f
                                dragY = 0f
                                if (rating != null) {
                                    state.submitMicroCardRating(currentCard, rating)
                                }
                            },
                            onDragCancel = {
                                dragX = 0f
                                dragY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragX = (dragX + dragAmount.x).coerceIn(-240f, 240f)
                                dragY = (dragY + dragAmount.y).coerceIn(-200f, 80f)
                            },
                        )
                    }
                    .clickable(enabled = !state.currentCardRevealed) {
                        state.revealCurrentCard()
                    },
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isCompact) 18.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Top metadata & action row on card
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = currentCard.sourcePageTitle.ifBlank { strings.viewSourceNote },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                IconButton(
                                    onClick = { onEditCard(currentCard) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Rounded.Edit, strings.editCard, modifier = Modifier.size(17.dp))
                                }
                                IconButton(
                                    onClick = { onDeleteCard(currentCard) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        strings.deleteCard,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                val hasSliceOrContext =
                                    !currentCard.sliceAssetPath.isNullOrBlank() ||
                                        !currentCard.contextExcerpt.isNullOrBlank()
                                SaltAssistChip(
                                    onClick = {
                                        if (hasSliceOrContext) {
                                            state.activeSlicePreviewCard = currentCard
                                        } else {
                                            state.jumpToCardAnchor(currentCard)
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (hasSliceOrContext) strings.slicePreviewTitle else strings.sourceNoteShort,
                                            maxLines = 1,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (hasSliceOrContext) {
                                                Icons.Rounded.PhotoLibrary
                                            } else {
                                                Icons.AutoMirrored.Rounded.OpenInNew
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    },
                                )
                            }
                        }

                        HorizontalDivider()

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            MarkdownContent(
                                markdown = currentCard.front,
                                compact = isCompact,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (state.currentCardRevealed) {
                                HorizontalDivider()
                                MarkdownContent(
                                    markdown = currentCard.back,
                                    compact = isCompact,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                currentCard.sliceAssetPath?.let { sliceRel ->
                                    val thumb = remember(state.client.vaultPath, sliceRel) {
                                        loadImageBitmapFromVault(state.client.vaultPath, sliceRel)
                                    }
                                    if (thumb != null) {
                                        OutlinedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { state.activeSlicePreviewCard = currentCard },
                                            shape = MaterialTheme.shapes.medium,
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Image(
                                                    bitmap = thumb,
                                                    contentDescription = strings.slicePreviewTitle,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        strings.slicePreviewTitle,
                                                        style = MaterialTheme.typography.labelLarge,
                                                    )
                                                    currentCard.contextExcerpt?.takeIf { it.isNotBlank() }?.let { ex ->
                                                        Text(
                                                            text = ex,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = strings.microSessionTapToFlip,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Visual Swipe Direction Badge Overlay
                    pendingSwipeRating?.let { rating ->
                        val (label, containerColor, textColor) = when (rating) {
                            1 -> Triple(
                                strings.ratingAgain,
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.colorScheme.onErrorContainer,
                            )
                            4 -> Triple(
                                strings.ratingEasy,
                                MaterialTheme.colorScheme.tertiaryContainer,
                                MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            else -> Triple(
                                strings.ratingGood,
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Surface(
                            color = containerColor.copy(alpha = 0.92f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // Lower ~35%: One-Handed Bottom Thumb Zone
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxWidth()
                .widthIn(max = 760.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.currentCardRevealed) {
                Button(
                    onClick = { state.revealCurrentCard() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.showAnswer, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                if (state.geekSwipeEnabled) {
                    Text(
                        text = strings.microSessionSwipeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { state.submitMicroCardRating(currentCard, 1) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(strings.ratingAgain, maxLines = 1) }

                    FilledTonalButton(
                        onClick = { state.submitMicroCardRating(currentCard, 2) },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(strings.ratingHard, maxLines = 1) }

                    Button(
                        onClick = { state.submitMicroCardRating(currentCard, 3) },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(strings.ratingGood, maxLines = 1) }

                    FilledTonalButton(
                        onClick = { state.submitMicroCardRating(currentCard, 4) },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(strings.ratingEasy, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SlicePreviewDialog(
    vaultPath: String,
    card: ReviewCardViewDto,
    onDismiss: () -> Unit,
    onEnterFullPage: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val sliceBitmap = remember(vaultPath, card.sliceAssetPath) {
        card.sliceAssetPath?.let { loadImageBitmapFromVault(vaultPath, it) }
    }
    var zoom by remember(card.cardId) { mutableStateOf(1f) }
    var panX by remember(card.cardId) { mutableStateOf(0f) }
    var panY by remember(card.cardId) { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.slicePreviewTitle, style = MaterialTheme.typography.titleMedium)
                        if (card.sourcePageTitle.isNotBlank()) {
                            Text(
                                text = card.sourcePageTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, strings.close)
                    }
                }

                if (sliceBitmap != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .clipToBounds()
                            .pointerInput(card.cardId) {
                                detectTransformGestures { _, pan, z, _ ->
                                    zoom = (zoom * z).coerceIn(1f, 4.5f)
                                    if (zoom <= 1.02f) {
                                        panX = 0f
                                        panY = 0f
                                    } else {
                                        panX += pan.x
                                        panY += pan.y
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = sliceBitmap,
                            contentDescription = strings.slicePreviewTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = panX
                                    translationY = panY
                                },
                        )
                    }
                }

                card.contextExcerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MarkdownContent(
                                markdown = excerpt,
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.close)
                    }
                    Button(
                        onClick = onEnterFullPage,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.enterFullPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun AllCardsManagerView(
    state: SaltAppState,
    onEditCard: (FsrsCardStateDto) -> Unit,
    onDeleteCard: (FsrsCardStateDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatMetricCard(
                label = strings.dueTodayLabel,
                value = state.dueCards.size.toString(),
                modifier = Modifier.weight(1f),
            )
            StatMetricCard(
                label = strings.totalCardsLabel,
                value = state.allCards.size.toString(),
                modifier = Modifier.weight(1f),
            )
            val avgStab = if (state.allCards.isEmpty()) {
                0.0
            } else {
                state.allCards.map { it.stability }.average()
            }
            StatMetricCard(
                label = strings.avgIntervalLabel,
                value = "${((avgStab * 10).toInt() / 10.0)} ${strings.daysUnit}",
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.allCards, key = { it.cardId }) { card ->
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Style, null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MarkdownContent(markdown = card.front, compact = true)
                            Text(
                                text = "${strings.nextReviewPrefix}${formatUtcTimestamp(card.dueAtUtc)} · ${strings.reviewsCountPrefix}${card.reps}${strings.reviewsCountSuffix}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onEditCard(card) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Rounded.Edit, strings.editCard, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { onDeleteCard(card) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                strings.deleteCard,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        TextButton(onClick = { state.jumpToCardAnchor(card) }) {
                            Text(strings.sourceNoteShort)
                        }
                    }
                }
            }
        }
    }
}

private fun ReviewCardViewDto.toFsrsCardStateDto(): FsrsCardStateDto = FsrsCardStateDto(
    cardId = cardId,
    pageId = pageId,
    anchorHaloId = anchorHaloId,
    front = front,
    back = back,
    stability = stability,
    difficulty = difficulty,
    reps = reps,
    lapses = lapses,
    state = state,
    lastReviewUtc = lastReviewUtc,
    dueAtUtc = dueAtUtc,
)

@Composable
private fun FlashcardEditorDialog(
    title: String,
    initialFront: String,
    initialBack: String,
    onDismiss: () -> Unit,
    onSave: (front: String, back: String) -> Unit,
) {
    val strings = LocalSaltStrings.current
    var front by remember(initialFront) { mutableStateOf(initialFront) }
    var back by remember(initialBack) { mutableStateOf(initialBack) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text(strings.cardFrontLabel) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(strings.cardBackLabel) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(front, back) },
                enabled = front.isNotBlank() && back.isNotBlank(),
            ) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@Composable
private fun StatMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
