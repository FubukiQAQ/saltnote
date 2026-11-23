package com.saltnote.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.InboxItemViewDto
import com.saltnote.bridge.InboxKindDto
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.state.SaltAppState
import com.saltnote.ui.theme.SaltHaloAmber
import com.saltnote.ui.theme.SaltHaloGlacier
import com.saltnote.ui.theme.SaltHaloMineral

/**
 * Collapsible right-side Salt Tray (盐池抽屉) for Tablet & Desktop workspaces (v0.2-alpha Section 2.4).
 * Supports one-click insertion into the active Canvas/Markdown note AND long-press direct Salt Halo extraction.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaltTrayDrawer(
    state: SaltAppState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val unprocessed = remember(state.inboxItems.toList()) {
        state.inboxItems.filter { it.state == "unprocessed" }
    }
    var haloTargetItem by remember { mutableStateOf<InboxItemViewDto?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(strings.saltTrayTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${unprocessed.size} ${strings.notesCountSuffix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = strings.close, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider()

        if (unprocessed.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.inboxEmptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(unprocessed, key = { it.inboxId }) { item ->
                    val thumbRel = item.thumbAssetPath
                        ?: if (item.kind == InboxKindDto.PhotoNote) item.primaryAssetPath else null
                    val bmp = remember(state.client.vaultPath, thumbRel) {
                        thumbRel?.let { loadImageBitmapFromVault(state.client.vaultPath, it) }
                    }
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { state.consumeInboxIntoActivePage(item) },
                                onLongClick = {
                                    if (item.kind == InboxKindDto.PhotoNote) {
                                        haloTargetItem = item
                                    }
                                },
                            ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val icon = when (item.kind) {
                                    InboxKindDto.PhotoNote -> Icons.Rounded.CameraAlt
                                    InboxKindDto.VoiceMemo -> Icons.Rounded.GraphicEq
                                    else -> Icons.Rounded.EditNote
                                }
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                Text(
                                    text = formatUtcTimestamp(item.createdAtUtc).takeLast(5),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.isPrivate) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = strings.privateLabel,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { state.deleteInboxItem(item.inboxId) },
                                    modifier = Modifier.size(22.dp),
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(14.dp))
                                }
                            }

                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = item.textNote,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(104.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }

                            if (item.kind == InboxKindDto.VoiceMemo) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val isPlaying = state.playingAudioInboxId == item.inboxId
                                    IconButton(
                                        onClick = { state.togglePlayInboxAudio(item) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    val secs = (item.audioDurationMs / 1000).coerceAtLeast(1)
                                    Text("${secs}s", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            val displaySnippet = item.textNote
                                ?: item.summaryText
                                ?: item.transcriptText
                            displaySnippet?.takeIf { it.isNotBlank() }?.let { txt ->
                                Text(
                                    text = txt,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = { state.consumeInboxIntoActivePage(item) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(strings.insertIntoPage, style = MaterialTheme.typography.labelMedium)
                                }
                                if (item.kind == InboxKindDto.PhotoNote) {
                                    IconButton(
                                        onClick = { haloTargetItem = item },
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.AutoAwesome,
                                            contentDescription = strings.haloExtractPromptTitle,
                                            tint = SaltHaloMineral,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    haloTargetItem?.let { target ->
        AlertDialog(
            onDismissRequest = { haloTargetItem = null },
            title = { Text(strings.haloExtractPromptTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            state.triggerInboxDirectHalo(target, "builtin.mineral_ocr")
                            haloTargetItem = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SaltHaloMineral))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.calloutMineralTitle)
                    }
                    FilledTonalButton(
                        onClick = {
                            state.triggerInboxDirectHalo(target, "builtin.glacier_card")
                            haloTargetItem = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SaltHaloGlacier))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.calloutGlacierTitle)
                    }
                    FilledTonalButton(
                        onClick = {
                            state.triggerInboxDirectHalo(target, "builtin.amber_expand")
                            haloTargetItem = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SaltHaloAmber))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.calloutAmberTitle)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { haloTargetItem = null }) { Text(strings.cancel) }
            },
        )
    }
}
