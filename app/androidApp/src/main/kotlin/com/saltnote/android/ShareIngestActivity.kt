package com.saltnote.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.saltnote.bridge.InboxCaptureInputDto
import com.saltnote.bridge.InboxKindDto
import com.saltnote.ui.i18n.resolveAppLanguage
import com.saltnote.ui.i18n.stringsFor
import com.saltnote.ui.platform.encodeImageBytesToWebpPair
import com.saltnote.ui.platform.systemLanguageTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Silent translucent Android `ACTION_SEND` share receiver (v0.2-alpha Section 2.1.4).
 * Ingests shared text clips and photos straight into the Salt Inbox via the shared
 * [SaltClientRegistry] engine, shows a brief Toast, and finishes without launching
 * the main workspace UI.
 */
class ShareIngestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent
        if (incoming?.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val mime = incoming.type.orEmpty()
        val sharedText = listOfNotNull(
            incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            incoming.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() },
        ).joinToString("\n").trim()
        val streamUri = IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)

        if (sharedText.isBlank() && streamUri == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val savedId = withContext(Dispatchers.IO) {
                runCatching {
                    val client = SaltClientRegistry.getOrOpen(applicationContext)
                    val strings = stringsFor(
                        resolveAppLanguage(
                            client.getRouterConfig().uiLanguage,
                            systemLanguageTag(),
                        ),
                    )
                    if (mime.startsWith("image/") && streamUri != null) {
                        val rawBytes = contentResolver.openInputStream(streamUri)?.use { it.readBytes() }
                            ?: error("Cannot read shared image stream")
                        val webpPair = encodeImageBytesToWebpPair(rawBytes, isBoardMode = false)
                        val inboxId = client.captureInboxItem(
                            InboxCaptureInputDto(
                                kind = InboxKindDto.PhotoNote,
                                textNote = sharedText.ifBlank { null },
                                primaryMediaBytes = webpPair?.first ?: rawBytes,
                                primaryMediaExt = if (webpPair != null) "webp" else "jpg",
                                thumbWebpBytes = webpPair?.second,
                                audioDurationMs = 0,
                                isPrivate = false,
                            ),
                        )
                        client.flushDeltas()
                        runCatching { SaltInboxWidget().updateAll(applicationContext) }
                        inboxId to strings.inboxCapturedToast
                    } else if (sharedText.isNotBlank()) {
                        val kind = if (sharedText.contains("http://") || sharedText.contains("https://")) {
                            InboxKindDto.WebClip
                        } else {
                            InboxKindDto.QuickText
                        }
                        val inboxId = client.captureInboxItem(
                            InboxCaptureInputDto(
                                kind = kind,
                                textNote = sharedText,
                                isPrivate = false,
                            ),
                        )
                        client.flushDeltas()
                        runCatching { SaltInboxWidget().updateAll(applicationContext) }
                        inboxId to strings.inboxCapturedToast
                    } else {
                        null
                    }
                }.getOrNull()
            }

            if (savedId != null) {
                SaltClientRegistry.notifyIngested()
                if (!SaltClientRegistry.isMainActivityActive.get()) {
                    SaltClientRegistry.triggerBestEffortBackgroundSync(applicationContext)
                }
                Toast.makeText(applicationContext, savedId.second, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
