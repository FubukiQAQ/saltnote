package com.saltnote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saltnote.bridge.InboxItemViewDto
import com.saltnote.bridge.InboxKindDto
import com.saltnote.ui.components.AutoSyncStatusPill
import com.saltnote.ui.components.SaltAssistChip
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.PlatformVoiceRecorderSession
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.platform.hasMicrophonePermission
import com.saltnote.ui.platform.loadImageBitmapFromVault
import com.saltnote.ui.platform.requestMicrophonePermission
import com.saltnote.ui.platform.startVoiceRecording
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import kotlinx.coroutines.delay

@Composable
fun FragmentHubScreen(
    state: SaltAppState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    var previewPhotoItem by remember { mutableStateOf<InboxItemViewDto?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 5-Minute Micro-Review Entry Card (No intimidating red overdue numbers!)
        item(key = "hub-micro-review") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
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
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Style,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Column {
                                Text(strings.hubMicroReviewTitle, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (state.dueCards.isEmpty()) {
                                        strings.hubNoCardsDue
                                    } else {
                                        "${(state.microSessionBudgetSecs / 60).coerceAtLeast(1)} min · ${strings.microSessionRescueMode}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.dueCards.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(180 to "3m", 300 to "5m", 600 to "10m").forEach { (secs, label) ->
                            SaltFilterChip(
                                selected = state.microSessionBudgetSecs == secs,
                                onClick = { state.microSessionBudgetSecs = secs },
                                label = { Text(label) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                state.selectedTab = NavTab.Review
                                state.startMicroReviewSession(budgetSeconds = state.microSessionBudgetSecs)
                            },
                            enabled = state.dueCards.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.hubReadyStart)
                        }
                    }
                }
            }
        }

        // 2. Quick Capture Row (Photo / Voice / QuickText)
        item(key = "hub-quick-capture") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { state.showCameraCaptureModal = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.capturePhoto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { state.showVoiceCaptureModal = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.captureVoice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { state.showQuickTextCaptureModal = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.EditNote, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.captureText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // 3. Salt Inbox Timeline Header
        item(key = "hub-inbox-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${strings.inboxTitle} (${state.inboxItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AutoSyncStatusPill(
                        status = state.autoSyncStatus,
                        onClick = { state.triggerManualAutoSync() },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = strings.inboxShowArchived,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = state.showArchivedInbox,
                        onCheckedChange = { state.toggleShowArchivedInbox(it) },
                    )
                }
            }
        }

        if (state.inboxItems.isEmpty()) {
            item(key = "hub-inbox-empty") {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = strings.inboxEmptyHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.inboxItems, key = { it.inboxId }) { item ->
                InboxTimelineCard(
                    item = item,
                    vaultPath = state.client.vaultPath,
                    isEnriching = item.inboxId in state.enrichingInboxIds,
                    isPlaying = state.playingAudioInboxId == item.inboxId,
                    onTogglePrivate = { state.toggleInboxItemPrivate(item) },
                    onEnrich = { state.enrichInboxItem(item.inboxId) },
                    onTogglePlayAudio = { state.togglePlayInboxAudio(item) },
                    onPreviewImage = { previewPhotoItem = item },
                    onConsumeIntoPage = { state.consumeInboxIntoActivePage(item) },
                    onDelete = { state.deleteInboxItem(item.inboxId) },
                )
            }
        }
    }

    previewPhotoItem?.let { item ->
        val rel = item.primaryAssetPath ?: item.thumbAssetPath
        if (rel != null) {
            InboxImageLightboxDialog(
                vaultPath = state.client.vaultPath,
                relPath = rel,
                caption = item.textNote ?: item.summaryText,
                onDismiss = { previewPhotoItem = null },
            )
        }
    }
}

@Composable
private fun InboxTimelineCard(
    item: InboxItemViewDto,
    vaultPath: String,
    isEnriching: Boolean,
    isPlaying: Boolean,
    onTogglePrivate: () -> Unit,
    onEnrich: () -> Unit,
    onTogglePlayAudio: () -> Unit,
    onPreviewImage: () -> Unit,
    onConsumeIntoPage: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    val thumbPath = item.thumbAssetPath ?: if (item.kind == InboxKindDto.PhotoNote) item.primaryAssetPath else null
    val thumbBmp = remember(vaultPath, thumbPath) {
        thumbPath?.let { loadImageBitmapFromVault(vaultPath, it) }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val kindIcon = when (item.kind) {
                    InboxKindDto.PhotoNote -> Icons.Rounded.CameraAlt
                    InboxKindDto.VoiceMemo -> Icons.Rounded.GraphicEq
                    else -> Icons.Rounded.EditNote
                }
                Icon(kindIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    text = formatUtcTimestamp(item.createdAtUtc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePrivate, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (item.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = strings.privateLabel,
                        tint = if (item.isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = strings.confirmDelete,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (thumbBmp != null) {
                Image(
                    bitmap = thumbBmp,
                    contentDescription = item.textNote ?: strings.capturePhoto,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPreviewImage() },
                )
            }

            if (item.kind == InboxKindDto.VoiceMemo) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onTogglePlayAudio, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) strings.audioPause else strings.audioPlay,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val secs = (item.audioDurationMs / 1000).coerceAtLeast(1)
                        Text(
                            text = "${secs}s · Opus",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item.textNote?.takeIf { it.isNotBlank() }?.let { note ->
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }

            item.summaryText?.takeIf { it.isNotBlank() }?.let { summary ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            item.transcriptText?.takeIf { it.isNotBlank() }?.let { transcript ->
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.autoTags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.autoTags.take(3).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusLabel = when {
                    isEnriching || item.transcriptStatus == "processing" -> strings.inboxStatusProcessing
                    item.transcriptStatus == "waiting_for_lan_relay" -> strings.inboxStatusWaitingLan
                    item.transcriptStatus == "completed" -> strings.inboxStatusCompleted
                    item.transcriptStatus == "failed" -> strings.inboxStatusFailed
                    item.transcriptStatus == "pending" -> strings.inboxStatusPending
                    else -> null
                }
                if (statusLabel != null) {
                    SaltAssistChip(
                        onClick = onEnrich,
                        enabled = !isEnriching,
                        label = { Text(statusLabel) },
                        leadingIcon = if (isEnriching) {
                            { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp) }
                        } else {
                            { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(14.dp)) }
                        },
                    )
                } else {
                    SaltAssistChip(
                        onClick = onEnrich,
                        label = { Text(strings.inboxEnrichAction) },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(14.dp)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.state == "unprocessed") {
                    TextButton(onClick = onConsumeIntoPage) {
                        Text(strings.insertIntoPage)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickVoiceCaptureDialog(
    initialPrivate: Boolean = false,
    onCaptured: (com.saltnote.ui.platform.RecordedAudioClip, Boolean, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var hasPermission by remember { mutableStateOf(hasMicrophonePermission()) }
    var isPrivate by remember { mutableStateOf(initialPrivate) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableStateOf(0) }
    var noteText by remember { mutableStateOf("") }
    var recorderSession by remember { mutableStateOf<PlatformVoiceRecorderSession?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            requestMicrophonePermission { granted ->
                hasPermission = granted
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            elapsedSec = 0
            while (isRecording) {
                delay(1_000L)
                elapsedSec++
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorderSession?.cancel()
        }
    }

    AlertDialog(
        onDismissRequest = {
            recorderSession?.cancel()
            onDismiss()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(strings.captureVoice)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasPermission) {
                    Text(strings.micPermissionPrompt, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(
                        onClick = {
                            requestMicrophonePermission { granted -> hasPermission = granted }
                        },
                    ) { Text(strings.grantMicPermission) }
                } else {
                    Surface(
                        onClick = {
                            if (!isRecording) {
                                errorMsg = null
                                val session = startVoiceRecording { err -> errorMsg = err }
                                if (session != null) {
                                    recorderSession = session
                                    isRecording = true
                                }
                            } else {
                                isRecording = false
                                val clip = recorderSession?.stopAndRead()
                                recorderSession = null
                                if (clip != null) {
                                    onCaptured(clip, isPrivate, noteText.trim().ifBlank { null })
                                } else {
                                    errorMsg = "Recording was empty"
                                }
                            }
                        },
                        shape = CircleShape,
                        color = if (isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(84.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    val mins = elapsedSec / 60
                    val secs = elapsedSec % 60
                    val timeLabel = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                    Text(
                        text = if (isRecording) "$timeLabel · ${strings.voiceRecordingHint}" else strings.voiceRecordStart,
                        style = MaterialTheme.typography.titleSmall,
                    )

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text(strings.captureText) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (isRecording) {
                Button(
                    onClick = {
                        isRecording = false
                        val clip = recorderSession?.stopAndRead()
                        recorderSession = null
                        if (clip != null) {
                            onCaptured(clip, isPrivate, noteText.trim().ifBlank { null })
                        } else {
                            errorMsg = "Recording was empty"
                        }
                    },
                ) { Text(strings.voiceRecordStop) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    recorderSession?.cancel()
                    onDismiss()
                },
            ) { Text(strings.cancel) }
        },
    )
}

@Composable
fun QuickTextCaptureDialog(
    initialPrivate: Boolean = false,
    onCaptured: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalSaltStrings.current
    var text by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(initialPrivate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(strings.captureText)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(strings.editorPlaceholder) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onCaptured(text, isPrivate) },
            ) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@Composable
fun InboxImageLightboxDialog(
    vaultPath: String,
    relPath: String,
    caption: String?,
    onDismiss: () -> Unit,
) {
    val bmp = remember(vaultPath, relPath) { loadImageBitmapFromVault(vaultPath, relPath) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.92f),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(relPath) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = caption,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                }
                caption?.takeIf { it.isNotBlank() }?.let { txt ->
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(20.dp),
                    ) {
                        Text(
                            text = txt,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
