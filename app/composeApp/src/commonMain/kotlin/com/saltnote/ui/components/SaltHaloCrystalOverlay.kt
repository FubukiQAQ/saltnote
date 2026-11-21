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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saltnote.ui.state.ActiveCrystalBubble
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral

@Composable
fun SaltHaloCrystalOverlay(
    bubble: ActiveCrystalBubble,
    onToggleCollapse: () -> Unit,
    onCloseAndCancel: () -> Unit,
    onFuseIntoBody: () -> Unit,
    onImportCards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (bubble.presetId) {
        "builtin.amber_expand" -> SaltHaloAmber
        "builtin.glacier_card" -> SaltHaloGlacier
        "builtin.mineral_ocr" -> SaltHaloMineral
        else -> MaterialTheme.colorScheme.primary
    }

    ElevatedCard(
        modifier = modifier.widthIn(min = 280.dp, max = 440.dp),
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
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = bubble.presetName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onToggleCollapse, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (bubble.isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                            contentDescription = "折叠盐晶气泡",
                        )
                    }
                    IconButton(onClick = onCloseAndCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭并中止流式请求",
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
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp, max = 280.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = bubble.markdown.ifBlank { "正在生成…" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        if (bubble.cardDrafts.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "提取到 ${bubble.cardDrafts.size} 张 FSRS 闪卡草稿",
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
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "Q${idx + 1}: ${draft.front}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "A: ${draft.back}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (!bubble.isStreaming && bubble.errorMessage == null && bubble.markdown.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = onFuseIntoBody,
                                    enabled = !bubble.fused && bubble.persistedHalo != null,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.MergeType, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (bubble.fused) "已熔合正文" else "一键熔合进正文")
                                }

                                if (bubble.cardDrafts.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = onImportCards,
                                        enabled = !bubble.cardsImported,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.Style, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (bubble.cardsImported) "已入库 FSRS" else "一键入库 FSRS")
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
