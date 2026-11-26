package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.state.AutoSyncStateKind
import com.saltnote.ui.state.AutoSyncStatus

/**
 * Material 3 FilterChip hardened against Compose Multiplatform / Skia hover-shadow artifacts:
 * Material3's default `filterChipElevation()` sets `hoveredElevation = 1.dp` while unselected
 * `containerColor = Color.Transparent`, causing drop shadows to bleed through the pill body
 * with a rectangular cutout behind the label on cursor/stylus hover.
 */
@Composable
fun SaltFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedLabelColor,
            selectedLeadingIconColor = selectedLabelColor,
            selectedTrailingIconColor = selectedLabelColor,
        ),
        elevation = FilterChipDefaults.filterChipElevation(
            elevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = Color.Transparent,
            borderWidth = 1.dp,
        ),
    )
}

/**
 * Material 3 AssistChip with solid surface container and zero hover-elevation shadow bleed.
 */
@Composable
fun SaltAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    border: BorderStroke? = null,
) {
    AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.primary,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        elevation = AssistChipDefaults.assistChipElevation(
            elevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        border = border ?: AssistChipDefaults.assistChipBorder(
            enabled = enabled,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            borderWidth = 1.dp,
        ),
    )
}

/**
 * Calm, unobtrusive status pill displaying background Salt Inbox / Vault auto-sync state.
 * Tapping triggers an immediate best-effort sync pass.
 */
@Composable
fun AutoSyncStatusPill(
    status: AutoSyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalSaltStrings.current
    val (label, dotColor) = when (status.kind) {
        AutoSyncStateKind.Syncing -> strings.autoSyncStatusSyncing to MaterialTheme.colorScheme.primary
        AutoSyncStateKind.Synced -> strings.autoSyncStatusSynced to Color(0xFF2E7D32)
        AutoSyncStateKind.PendingOffline -> strings.autoSyncStatusPending to Color(0xFFED6C02)
        AutoSyncStateKind.Error -> strings.autoSyncStatusError to MaterialTheme.colorScheme.error
        AutoSyncStateKind.Idle -> strings.autoSyncStatusIdle to MaterialTheme.colorScheme.outline
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status.kind == AutoSyncStateKind.Syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.8.dp,
                    color = dotColor,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
