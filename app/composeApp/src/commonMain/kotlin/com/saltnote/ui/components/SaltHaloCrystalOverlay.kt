package com.saltnote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.state.ActiveCrystalBubble
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral

@Composable
fun SaltHaloCrystalOverlay(
    bubble: ActiveCrystalBubble,
    onToggleCollapse: () -> Unit,
    onDeleteHalo: () -> Unit = {},
    onCloseAndCancel: () -> Unit,
    onFuseIntoBody: () -> Unit,
    onImportCards: () -> Unit,
    onRemoveDraftCard: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val isGlacierCard = bubble.presetId == "builtin.glacier_card"
    val presetDisplayName = when (bubble.presetId) {
        "builtin.amber_expand" -> strings.haloAmberShort
        "builtin.glacier_card" -> strings.haloGlacierShort
        "builtin.mineral_ocr" -> strings.haloMineralShort
        else -> bubble.presetName
    }
    val accent = when (bubble.presetId) {
        "builtin.amber_expand" -> SaltHaloAmber
        "builtin.glacier_card" -> SaltHaloGlacier
        "builtin.mineral_ocr" -> SaltHaloMineral
        else -> MaterialTheme.colorScheme.primary
    }
    var showRawFallback by remember(bubble.presetId, bubble.persistedHalo?.haloId) { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier.widthIn(min = 300.dp, max = 460.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.5.dp, accent.copy(alpha = 0.75f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isGlacierCard) Icons.Rounded.Style else Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = presetDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!bubble.isStreaming && bubble.persistedHalo != null) {
                        IconButton(onClick = onDeleteHalo, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = strings.deleteHaloDesc,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(onClick = onToggleCollapse, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (bubble.isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                            contentDescription = strings.collapseBubbleDesc,
                        )
                    }
                    IconButton(onClick = onCloseAndCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = strings.closeAndCancelBubbleDesc,
                        )
                    }
                }

                if (bubble.isStreaming) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                }

                AnimatedVisibility(visible = !bubble.isCollapsed) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (bubble.errorMessage != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        text = bubble.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        } else if (isGlacierCard) {
                            // Glacier Card mode: NEVER show raw JSON; display ONLY structured cards!
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp, max = 340.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (bubble.isStreaming && bubble.cardDrafts.isEmpty()) {
                                    Text(
                                        text = strings.extractingCardsEllipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (bubble.cardDrafts.isNotEmpty()) {
                                    Text(
                                        text = "${strings.extractedCardsPrefix}${bubble.cardDrafts.size}${strings.extractedCardsSuffix}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SaltHaloGlacier,
                                    )
                                    bubble.cardDrafts.forEachIndexed { idx, draft ->
                                        OutlinedCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "${strings.cardPrefix}${idx + 1}",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = SaltHaloGlacier,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    if (!bubble.cardsImported && bubble.cardDrafts.size > 1) {
                                                        IconButton(
                                                            onClick = { onRemoveDraftCard(idx) },
                                                            modifier = Modifier.size(24.dp),
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.DeleteOutline,
                                                                contentDescription = strings.removeDraftCardDesc,
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                                MarkdownContent(markdown = draft.front, compact = true)
                                                HorizontalDivider()
                                                MarkdownContent(markdown = draft.back, compact = true)
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = strings.noValidCardsMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (bubble.markdown.isNotBlank()) {
                                        TextButton(onClick = { showRawFallback = !showRawFallback }) {
                                            Text(if (showRawFallback) strings.hideRawOutput else strings.viewRawOutput)
                                        }
                                        if (showRawFallback) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = bubble.markdown,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                    modifier = Modifier.padding(8.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Amber Expand / Mineral OCR: render full Markdown + LaTeX live!
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp, max = 340.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (bubble.markdown.isBlank()) {
                                    Text(
                                        text = strings.generatingEllipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    MarkdownContent(
                                        markdown = bubble.markdown,
                                        compact = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        if (!bubble.isStreaming && bubble.errorMessage == null && bubble.markdown.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isGlacierCard && bubble.cardDrafts.isNotEmpty()) {
                                    Button(
                                        onClick = onImportCards,
                                        enabled = !bubble.cardsImported,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.Style, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (bubble.cardsImported) strings.importedToFsrs else strings.importToFsrs)
                                    }
                                    FilledTonalButton(
                                        onClick = onFuseIntoBody,
                                        enabled = !bubble.fused && bubble.persistedHalo != null,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.MergeType, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (bubble.fused) strings.insertedBodyShort else strings.insertBodyShort)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = onFuseIntoBody,
                                        enabled = !bubble.fused && bubble.persistedHalo != null,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.MergeType, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (bubble.fused) strings.fusedIntoBody else strings.fuseIntoBody)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
