package app.mini.hub.persistence

import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcActionStoreTest {

    @Test
    fun sqliteRestartRestoration_preservesDraftVersionsAndTruthfulProvenance() {
        val tempDb = File.createTempFile("mini-test-draft-restart", ".db").apply { delete(); deleteOnExit() }
        val store1 = JdbcActionStore(tempDb.path)

        val fullProvenance = DraftProvenance(
            accountId = "gmail:work",
            accountLabel = "Gmail · Work",
            userInstruction = "make it shorter and polite",
            sourceMessageReferences = listOf(
                DraftSourceReference(
                    messageId = "provider:msg_88",
                    senderHint = "Client",
                    timestampMillis = 1700000000000L,
                    snippet = "Can you send the breakdown?",
                    isIncludedAsText = true,
                ),
            ),
            appliedStyleSummary = "Style: Professional (concise, formal)",
            coverageWarning = "Partial history loaded",
            excludedAttachments = listOf("quote.pdf (attachment content not read by AI)"),
            previousDraft = "Earlier longer draft that was revised",
            understandingNote = "Client requested price breakdown with urgency",
            generatedAtMillis = 1700000005000L,
        )

        val versions = listOf(
            DraftVersion(text = "Initial draft", provenance = null, originalAiText = null, isAiGenerated = false),
            DraftVersion(text = "Shorter polite draft", provenance = fullProvenance, originalAiText = "Shorter polite draft", isAiGenerated = true),
        )

        val draft = ConversationDraft(
            conversationKey = "conv_restart_test",
            accountId = "gmail:work",
            body = "Shorter polite draft",
            to = "client@example.com",
            subject = "Project breakdown",
            isNewEmail = true,
            originalGeneratedDraft = "Shorter polite draft",
            updatedAtMillis = 1700000010000L,
            provenance = fullProvenance,
            versions = versions,
        )

        // Save in first store and close
        store1.saveDraft(draft)
        store1.close()

        // Reopen from disk in second store instance (simulating app process restart)
        val store2 = JdbcActionStore(tempDb.path)
        val restored = store2.loadDraft("conv_restart_test")
        store2.close()

        assertNotNull(restored)
        assertEquals("Shorter polite draft", restored.body)
        assertEquals("client@example.com", restored.to)
        assertEquals("Project breakdown", restored.subject)
        assertTrue(restored.isNewEmail)
        assertEquals("Shorter polite draft", restored.originalGeneratedDraft)

        // Verify truthful provenance survived restart
        val restoredProv = restored.provenance
        assertNotNull(restoredProv)
        assertEquals("gmail:work", restoredProv.accountId)
        assertEquals("Gmail · Work", restoredProv.accountLabel)
        assertEquals("make it shorter and polite", restoredProv.userInstruction)
        assertEquals("Earlier longer draft that was revised", restoredProv.previousDraft)
        assertEquals("Client requested price breakdown with urgency", restoredProv.understandingNote)
        assertEquals("Style: Professional (concise, formal)", restoredProv.appliedStyleSummary)
        assertEquals("Partial history loaded", restoredProv.coverageWarning)
        assertEquals(listOf("quote.pdf (attachment content not read by AI)"), restoredProv.excludedAttachments)
        assertEquals(1, restoredProv.sourceMessageReferences.size)
        assertEquals("provider:msg_88", restoredProv.sourceMessageReferences.first().messageId)

        // Verify coupled versions survived restart
        assertEquals(2, restored.versions.size)
        assertEquals("Initial draft", restored.versions[0].text)
        assertFalse(restored.versions[0].isAiGenerated)
        assertNull(restored.versions[0].provenance)

        assertEquals("Shorter polite draft", restored.versions[1].text)
        assertTrue(restored.versions[1].isAiGenerated)
        assertEquals(fullProvenance, restored.versions[1].provenance)
        assertEquals("Shorter polite draft", restored.versions[1].originalAiText)
    }

    @Test
    fun schemaMigration_addsMissingColumnsWithoutLosingExistingDrafts() {
        val tempDb = File.createTempFile("mini-test-migration", ".db").apply { delete(); deleteOnExit() }
        // Create an older schema table lacking provenance_json and versions_json
        DriverManager.getConnection("jdbc:sqlite:${tempDb.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    CREATE TABLE conversation_drafts (
                        conversation_key TEXT NOT NULL PRIMARY KEY,
                        account_id TEXT NOT NULL,
                        body TEXT NOT NULL,
                        compose_to TEXT NOT NULL,
                        compose_cc TEXT NOT NULL,
                        compose_bcc TEXT NOT NULL,
                        compose_subject TEXT NOT NULL,
                        is_new_email INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        original_generated_draft TEXT
                    )
                    """,
                )
                s.execute(
                    """
                    INSERT INTO conversation_drafts VALUES (
                        'legacy_key', 'gmail:work', 'Legacy draft body', 'bob@example.com', '', '', 'Legacy subject', 1, 1000, 'Legacy draft body'
                    )
                    """,
                )
            }
        }

        // Open with JdbcActionStore which executes ALTER TABLE migrations
        val store = JdbcActionStore(tempDb.path)
        val loaded = store.loadDraft("legacy_key")
        assertNotNull(loaded)
        assertEquals("Legacy draft body", loaded.body)
        assertEquals("bob@example.com", loaded.to)
        assertNull(loaded.provenance)
        assertEquals(1, loaded.versions.size)
        assertEquals("Legacy draft body", loaded.versions.first().text)

        // Now save a new draft with full provenance into the migrated database
        val newDraft = ConversationDraft(
            conversationKey = "new_key",
            accountId = "gmail:work",
            body = "New body",
            updatedAtMillis = 2000L,
            provenance = DraftProvenance("gmail:work", "Gmail Work", "instruct"),
        )
        store.saveDraft(newDraft)
        val loadedNew = store.loadDraft("new_key")
        assertNotNull(loadedNew)
        assertEquals("New body", loadedNew.body)
        assertNotNull(loadedNew.provenance)
        assertEquals("instruct", loadedNew.provenance?.userInstruction)
        store.close()
    }
}
