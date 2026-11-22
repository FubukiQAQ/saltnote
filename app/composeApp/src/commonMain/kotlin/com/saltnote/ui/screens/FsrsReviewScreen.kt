package com.saltnote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.FsrsCardStateDto
import com.saltnote.ui.components.MarkdownContent
import com.saltnote.ui.components.SaltAssistChip
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.state.SaltAppState

@Composable
fun FsrsReviewScreen(state: SaltAppState) {
    val strings = LocalSaltStrings.current
    var showCreateCardDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<FsrsCardStateDto?>(null) }
    var deletingCard by remember { mutableStateOf<FsrsCardStateDto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(strings.reviewTitle, style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = { showCreateCardDialog = true }) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.newCard)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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

        val currentCard = state.dueCards.firstOrNull()
        if (currentCard != null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 760.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${strings.remainingCardsPrefix}${state.dueCards.size}${strings.remainingCardsSuffix}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IconButton(
                                    onClick = { editingCard = currentCard },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Rounded.Edit, strings.editCard, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { deletingCard = currentCard },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        strings.deleteCard,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                SaltAssistChip(
                                    onClick = { state.jumpToCardAnchor(currentCard) },
                                    label = { Text(strings.viewSourceNote) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }

                        MarkdownContent(
                            markdown = currentCard.front,
                            compact = false,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (state.currentCardRevealed) {
                            HorizontalDivider()
                            MarkdownContent(
                                markdown = currentCard.back,
                                compact = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { state.submitReviewRating(currentCard, 1) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) { Text(strings.ratingAgain) }
                                FilledTonalButton(
                                    onClick = { state.submitReviewRating(currentCard, 2) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(strings.ratingHard) }
                                Button(
                                    onClick = { state.submitReviewRating(currentCard, 3) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(strings.ratingGood) }
                                FilledTonalButton(
                                    onClick = { state.submitReviewRating(currentCard, 4) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(strings.ratingEasy) }
                            }
                        } else {
                            Button(
                                onClick = { state.revealCurrentCard() },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.showAnswer)
                            }
                        }
                    }
                }
            }
        } else {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(strings.allReviewsDone, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Text("${strings.allCardsLabel} (${state.allCards.size})", style = MaterialTheme.typography.titleMedium)
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
                            onClick = { editingCard = card },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Rounded.Edit, strings.editCard, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { deletingCard = card },
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
