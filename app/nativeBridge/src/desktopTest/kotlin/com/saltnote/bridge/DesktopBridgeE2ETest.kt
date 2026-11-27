package com.saltnote.bridge

import kotlinx.coroutines.runBlocking
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

    @Test
    fun inboxServiceAndMicroReviewSessionRoundtripOverUniFfi() = runBlocking {
        val tempVault = Files.createTempDirectory("saltnote_kt_v02_").toFile()
        try {
            val secrets = InMemorySecretVault()
            val client = openSaltNoteClient(
                vaultPath = tempVault.absolutePath,
                deviceName = "Kotlin-Phone-Test",
                secretVault = secrets,
            )

            val page = client.createPage("高数复习", "study", false, listOf("math"), "# 格林公式")
            val inboxId = client.captureInboxItem(
                InboxCaptureInputDto(
                    kind = InboxKindDto.VoiceMemo,
                    textNote = "课后思考",
                    primaryMediaBytes = "FAKE_OGG_OPUS".toByteArray(),
                    primaryMediaExt = "ogg",
                    thumbWebpBytes = null,
                    audioDurationMs = 7_500,
                    isPrivate = true,
                ),
            )
            assertTrue(inboxId.startsWith("inbx_"))

            val items = client.listInboxItems(includeArchived = false, limit = 20)
            assertEquals(1, items.size)
            assertEquals("waiting_for_lan_relay", items[0].transcriptStatus)

            // Async UniFFI enrich_item on #private offline must remain waiting_for_lan_relay
            val enriched = client.enrichInboxItem(inboxId)
            assertEquals("waiting_for_lan_relay", enriched.transcriptStatus)

            client.consumeInboxIntoPage(inboxId, page.pageId, "blk_target")
            assertTrue(client.listInboxItems(includeArchived = false, limit = 20).isEmpty())
            assertEquals("archived", client.listInboxItems(includeArchived = true, limit = 20)[0].state)

            // Micro-session plan over async IntelService
            client.createFlashcard(page.pageId, null, "格林公式条件？", "闭合光滑边界 + 偏导连续")
            val plan = client.buildMicroReviewSession(budgetSeconds = 300, energyLevel = 2, rescueMode = true)
            assertEquals(300, plan.budgetSeconds)
            assertEquals(1, plan.totalDueCount)
            assertEquals(1, plan.cards.size)
            assertEquals("高数复习", plan.cards[0].sourcePageTitle)
        } finally {
            tempVault.deleteRecursively()
        }
    }

    @Test
    fun lanPairingAndPairedBatchInboxSyncOverUniFfi() {
        val phoneDir = Files.createTempDirectory("saltnote_kt_lan_phone_").toFile()
        val pcDir = Files.createTempDirectory("saltnote_kt_lan_pc_").toFile()
        try {
            val phone = openSaltNoteClient(
                vaultPath = phoneDir.absolutePath,
                deviceName = "Kotlin-Phone",
                secretVault = InMemorySecretVault(),
            )
            val pc = openSaltNoteClient(
                vaultPath = pcDir.absolutePath,
                deviceName = "Kotlin-PC",
                secretVault = InMemorySecretVault(),
            )

            val pcInfo = pc.startLanP2p(enableMdns = false)
            assertTrue(pcInfo.pairingToken.length >= 16)
            assertEquals(6, pcInfo.pairingPin.length)

            val pairRes = phone.pairWithLanPeer(
                host = "127.0.0.1",
                port = pcInfo.port,
                expectedFingerprint = pcInfo.fingerprint,
                pairingSecret = pcInfo.pairingToken,
            )
            assertEquals(pcInfo.deviceId, pairRes.peerDeviceId)
            assertEquals(1, phone.listPairedPeers().size)
            assertEquals(1, pc.listPairedPeers().size)

            val inboxId = phone.captureInboxItem(
                InboxCaptureInputDto(
                    kind = InboxKindDto.PhotoNote,
                    textNote = "黑板推导自动同步测试",
                    primaryMediaBytes = "FAKE_WEBP_PHOTO".toByteArray(),
                    primaryMediaExt = "webp",
                    thumbWebpBytes = "FAKE_THUMB".toByteArray(),
                    isPrivate = false,
                ),
            )
            assertTrue(inboxId.startsWith("inbx_"))

            val batch = phone.syncAllPairedLanPeers()
            assertEquals(1, batch.succeededPeers)
            assertEquals(1, batch.totalSentPackets)
            assertEquals(2, batch.totalSentAssets)
            assertEquals(1, pc.listInboxItems(includeArchived = false, limit = 20).size)
            assertTrue(pc.getInboxSyncRevision() >= 1L)

            val prefs = phone.getSyncPrefs().copy(mirrorWebdavAfterLan = true)
            phone.saveSyncPrefs(prefs)
            assertTrue(phone.getSyncPrefs().mirrorWebdavAfterLan)

            phone.stopLanP2p()
            pc.stopLanP2p()
        } finally {
            phoneDir.deleteRecursively()
            pcDir.deleteRecursively()
        }
    }

    @Test
    fun multiCanvasMultiDocSearchAndUiPrefsRoundtripOverUniFfi() {
        val tempVault = Files.createTempDirectory("saltnote_kt_v03_").toFile()
        try {
            val client = openSaltNoteClient(
                vaultPath = tempVault.absolutePath,
                deviceName = "Kotlin-v03-Test",
                secretVault = InMemorySecretVault(),
            )
            val page = client.createPage("有机化学", "study", false, listOf("chem"), "# 第一部分")
            val c2 = client.createPageCanvas(page.pageId, "合成路线草稿")
            assertTrue(c2.canvasId.startsWith("cvs_"))
            client.renamePageCanvas(page.pageId, c2.canvasId, "机理推导")

            val d2 = client.createPageDocument(page.pageId, "反应机理附页", "傅克酰基化反应与亲电取代")
            assertTrue(d2.docId.startsWith("doc_"))
            client.updatePageDocumentBody(page.pageId, d2.docId, "傅克酰基化反应与重排抑制")
            client.renamePageDocument(page.pageId, d2.docId, "重点机理")

            val snap = client.getPageSnapshot(page.pageId)
            assertEquals(2, snap.canvases.count { !it.isInline })
            assertEquals(2, snap.documents.size)
            assertEquals("机理推导", snap.canvases.first { it.canvasId == c2.canvasId }.title)
            assertEquals("重点机理", snap.documents.first { it.docId == d2.docId }.title)
            assertTrue(snap.documents.first { it.docId == d2.docId }.bodyMarkdown.contains("重排抑制"))

            // FTS5 search must hit text inside secondary sub-document
            val hits = client.searchNotes("重排抑制", 10)
            assertTrue(hits.any { it.pageId == page.pageId })

            // Local UI prefs & recent visits
            val initialPrefs = client.getUiPrefs()
            assertEquals(5, initialPrefs.penPaletteArgb.size)
            val updatedPalette = initialPrefs.penPaletteArgb.toMutableList().apply {
                this[1] = 0xFFFF6B6BL
            }
            client.saveUiPrefs(
                initialPrefs.copy(
                    penPaletteArgb = updatedPalette,
                    recentCustomColorsArgb = listOf(0xFFFF6B6BL),
                ),
            )
            client.recordPageVisit(page.pageId, "study", c2.canvasId, d2.docId)
            val card = client.createFlashcard(page.pageId, null, "傅克反应催化剂？", "无水 AlCl3")
            val prefsAfter = client.recordCardVisit(card.cardId, page.pageId)
            assertEquals(0xFFFF6B6BL, prefsAfter.penPaletteArgb[1])
            assertEquals(page.pageId, prefsAfter.recentPages.first().pageId)
            assertEquals(d2.docId, prefsAfter.recentPages.first().lastDocId)
            assertEquals(card.cardId, prefsAfter.recentCards.first().cardId)

            // Deleting secondary canvas & doc leaves primary intact
            client.deletePageCanvas(page.pageId, c2.canvasId)
            client.deletePageDocument(page.pageId, d2.docId)
            val summary = client.listPages().first { it.meta.pageId == page.pageId }
            assertEquals(1, summary.canvases.size)
            assertEquals(1, summary.documents.size)
        } finally {
            tempVault.deleteRecursively()
        }
    }
}
