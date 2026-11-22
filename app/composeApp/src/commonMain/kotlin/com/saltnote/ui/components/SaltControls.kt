package com.saltnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
