package com.saltnote.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class DesktopBridgeE2ETest {

    private class InMemorySecretVault : SecretVault {
        private val map = mutableMapOf<String, String>()
        override fun getSecret(keyRef: String): String? = map[keyRef]
        override fun putSecret(keyRef: String, secretValue: String) {
            map[keyRef] = secretValue
        }
        override fun deleteSecret(keyRef: String) {
            map.remove(keyRef)
        }
    }

    @Test
    fun kotlinToRustUniFfiFullLoopAndPrivacyAudit() {
        val tempVault = Files.createTempDirectory("saltnote_kt_e2e_").toFile()
        try {
            val secrets = InMemorySecretVault()
            secrets.putSecret("vault://openai_api_key", "sk-TEST-ONLY-NEVER-DISK-99999")
            val client = openSaltNoteClient(
                vaultPath = tempVault.absolutePath,
                deviceName = "Kotlin-Desktop-Test",
                secretVault = secrets,
            )

            // 1. Welcome page + physical Vault structure
            val welcome = client.ensureWelcomePage()
            assertTrue(File(tempVault, "pages").isDirectory)
            assertTrue(File(tempVault, "assets").isDirectory)
            assertTrue(File(tempVault, ".saltnote/config/router.json").isFile)
            assertTrue(File(tempVault, ".saltnote/config/halo_presets.json").isFile)
            assertTrue(File(tempVault, ".saltnote/local/index.sqlite").isFile)

            // 2. Binary 16-byte LE stroke ingestion -> R-Tree query
            val pts = (0 until 20).map { i ->
                StrokeSample(x = 50f + i * 5f, y = 80f + i * 2f, pressure = 0.7f, dtMs = 4)
            }
            val ingested = client.ingestRawStroke(
                pageId = welcome.pageId,
                canvasId = "main",
                brushKind = "pen",
                colorArgb = 0xFF191C1EL,
                baseWidth = 3f,
                points = pts,
            )
            assertTrue(ingested.renderStroke.pointsXyp.size >= 12)
            val strokes = client.getCanvasStrokes(welcome.pageId, "main", 0f, 0f, 500f, 500f)
            assertEquals(1, strokes.size)

            // 3. FTS5 + prefix + CJK + symbol search
            val hits = client.searchNotes("Salt", 10)
            assertTrue(hits.any { it.pageId == welcome.pageId })
            assertTrue(client.searchNotes("双模记录", 10).any { it.pageId == welcome.pageId })
            assertTrue(client.searchNotes("#private", 10).any { it.pageId == welcome.pageId })

            // 4. FSRS card create, review, edit & tombstone delete
            val card = client.createFlashcard(welcome.pageId, null, "Loro CRDT 作用？", "多端离线无冲突协同")
            assertTrue(client.listDueCards().any { it.cardId == card.cardId })
            val afterReview = client.submitCardReview(welcome.pageId, card.cardId, 3, 2200)
            assertEquals(1, afterReview.reps)
            val updatedCard = client.updateFlashcard(welcome.pageId, card.cardId, "Loro CRDT 优势？", "零冲突离线合并")
            assertEquals("Loro CRDT 优势？", updatedCard.front)
            assertTrue(client.listAllCards().any { it.cardId == card.cardId && it.front == "Loro CRDT 优势？" && it.reps == 1 })
            client.deleteFlashcard(welcome.pageId, card.cardId)
            assertFalse(client.listAllCards().any { it.cardId == card.cardId })

            // 4b. Page creation, tag update & tombstone deletion
            val tempNote = client.createPage("待删临时页", "study", false, listOf("临时"), "临时正文")
            assertTrue(client.listPages().any { it.meta.pageId == tempNote.pageId })
            client.deletePage(tempNote.pageId)
            assertFalse(client.listPages().any { it.meta.pageId == tempNote.pageId })
            assertTrue(client.searchNotes("临时正文", 10).isEmpty())

            // 5. #private page hard-blocks cloud provider across UniFFI boundary + RouterConfig language persistence
            val rcfg = client.getRouterConfig()
            val updatedCfg = rcfg.copy(uiLanguage = "en", aiOutputLanguage = "ja")
            client.saveRouterConfig(updatedCfg)
            val reloadedCfg = client.getRouterConfig()
            assertEquals("en", reloadedCfg.uiLanguage)
            assertEquals("ja", reloadedCfg.aiOutputLanguage)

            client.updatePageMeta(welcome.pageId, isPrivate = true)
            assertThrows(Exception::class.java) {
                client.triggerTextHalo(
                    pageId = welcome.pageId,
                    presetId = "builtin.amber_expand",
                    anchorOffset = 0,
                    selectedText = "private text",
                    providerId = "openai-byok",
                    outputLanguageHint = "zh-CN",
                    listener = object : HaloStreamListener {
                        override fun onDelta(chunk: String) = Unit
                        override fun onComplete(fullMarkdown: String, halo: HaloAnnotationDto?) = Unit
                        override fun onError(code: String, message: String) = Unit
                    },
                )
            }

            // 6. Flush deltas and verify ZERO plaintext API key leakage across entire Vault!
            client.flushDeltas()
            tempVault.walkTopDown().filter { it.isFile }.forEach { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                assertFalse(text.contains("sk-TEST-ONLY"), "Plaintext API key leaked into ${f.absolutePath}")
            }

            // 7. Standard Markdown export backup
            val backupDir = Files.createTempDirectory("saltnote_md_backup_").toFile()
            try {
                val exportedCount = client.exportMarkdownBackup(backupDir.absolutePath)
                assertTrue(exportedCount >= 1)
                assertTrue(File(backupDir, "pages").listFiles().orEmpty().any { it.extension == "md" })
            } finally {
                backupDir.deleteRecursively()
            }
        } finally {
            tempVault.deleteRecursively()
        }
    }

    @Test
    fun desktopDpapiSecretVaultRoundtrip() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return
        val vault = DesktopSecretVault()
        val keyRef = "vault://unit_test_dpapi_key"
        val secret = "sk-dpapi-roundtrip-check-42"
        try {
            vault.putSecret(keyRef, secret)
            assertEquals(secret, vault.getSecret(keyRef))
        } finally {
            vault.deleteSecret(keyRef)
        }
    }
}
