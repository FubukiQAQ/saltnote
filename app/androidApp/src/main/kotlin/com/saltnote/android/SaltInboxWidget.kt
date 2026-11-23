package com.saltnote.android

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.saltnote.ui.i18n.resolveAppLanguage
import com.saltnote.ui.i18n.stringsFor
import com.saltnote.ui.platform.systemLanguageTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 4x1 Desktop Capsule App Widget (v0.2-alpha Section 2.1.4 & 2.2):
 * Provides one-tap Photo Capture, Voice Memo, and 5-Minute Micro-Review entry points
 * with a gentle presence indicator when cards await review (never intimidating overdue counts).
 */
class SaltInboxWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) {
            runCatching {
                val client = SaltClientRegistry.getOrOpen(context)
                val uiLang = client.getRouterConfig().uiLanguage
                val hasDue = client.listDueCards().isNotEmpty()
                val strs = stringsFor(resolveAppLanguage(uiLang, systemLanguageTag()))
                WidgetUiData(
                    hasDue = hasDue,
                    cameraLabel = strs.capturePhoto,
                    voiceLabel = strs.captureVoice,
                    reviewLabel = strs.navReview,
                )
            }.getOrElse {
                val strs = stringsFor(resolveAppLanguage("system", systemLanguageTag()))
                WidgetUiData(
                    hasDue = false,
                    cameraLabel = strs.capturePhoto,
                    voiceLabel = strs.captureVoice,
                    reviewLabel = strs.navReview,
                )
            }
        }

        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(24.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WidgetCapsuleButton(
                            emoji = "📷",
                            label = state.cameraLabel,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .fillMaxHeight()
                                .clickable(
                                    actionStartActivity(
                                        SaltClientRegistry.buildLaunchMainIntent(
                                            context,
                                            SaltClientRegistry.ACTION_CAPTURE_CAMERA,
                                        ),
                                    ),
                                ),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        WidgetCapsuleButton(
                            emoji = "🎙️",
                            label = state.voiceLabel,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .fillMaxHeight()
                                .clickable(
                                    actionStartActivity(
                                        SaltClientRegistry.buildLaunchMainIntent(
                                            context,
                                            SaltClientRegistry.ACTION_CAPTURE_VOICE,
                                        ),
                                    ),
                                ),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        WidgetCapsuleButton(
                            emoji = if (state.hasDue) "✨" else "⚡",
                            label = state.reviewLabel,
                            showDueDot = state.hasDue,
                            modifier = GlanceModifier
                                .defaultWeight()
                                .fillMaxHeight()
                                .clickable(
                                    actionStartActivity(
                                        SaltClientRegistry.buildLaunchMainIntent(
                                            context,
                                            SaltClientRegistry.ACTION_MICRO_REVIEW,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}

private data class WidgetUiData(
    val hasDue: Boolean,
    val cameraLabel: String,
    val voiceLabel: String,
    val reviewLabel: String,
)

@androidx.compose.runtime.Composable
private fun WidgetCapsuleButton(
    emoji: String,
    label: String,
    modifier: GlanceModifier = GlanceModifier,
    showDueDot: Boolean = false,
) {
    Row(
        modifier = modifier
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(18.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = emoji,
            style = TextStyle(fontSize = 15.sp),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (showDueDot) {
            Spacer(GlanceModifier.width(4.dp))
            Box(
                modifier = GlanceModifier
                    .size(7.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(4.dp),
            ) {}
        }
    }
}

class SaltInboxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SaltInboxWidget()
}
