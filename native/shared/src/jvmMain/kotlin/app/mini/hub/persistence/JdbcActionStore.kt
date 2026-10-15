package app.mini.hub.persistence

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ActionState
import app.mini.hub.action.ApprovalRecord
import app.mini.hub.action.AttachmentDigest
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ReviewedActionSnapshot
import app.mini.hub.action.SourceVersion
import app.mini.hub.domain.UtcInstant
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Unencrypted sqlite-jdbc action store for the JVM target. It executes the same SQL schema
 * and migrations as the Android SQLCipher store, allowing host unit tests to verify real SQL
 * transactions, restart persistence and schema evolution. Test-only; never shipped.
 */
class JdbcActionStore(path: String) : ActionStore, ConversationDraftStore, AutoCloseable {
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.createStatement().use { s ->
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS approvals (
                    id TEXT NOT NULL PRIMARY KEY,
                    payload_digest TEXT NOT NULL,
                    approved_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    consumed INTEGER NOT NULL CHECK (consumed IN (0, 1))
                )
                """,
            )
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT NOT NULL PRIMARY KEY,
                    approval_id TEXT NOT NULL,
                    payload_digest TEXT NOT NULL,
                    route TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_attempt_at INTEGER,
                    receipt TEXT,
                    failure_reason TEXT,
                    FOREIGN KEY (approval_id) REFERENCES approvals(id)
                )
                """,
            )
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS reviewed_snapshots (
                    command_id TEXT NOT NULL PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    route TEXT NOT NULL,
                    recipients TEXT NOT NULL,
                    cc TEXT NOT NULL,
                    bcc TEXT NOT NULL,
                    subject TEXT,
                    body TEXT NOT NULL,
                    attachment_names TEXT NOT NULL,
                    reply_target TEXT,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    receipt TEXT,
                    failure_reason TEXT
                )
                """,
            )
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS conversation_drafts (
                    conversation_key TEXT NOT NULL PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    body TEXT NOT NULL,
                    compose_to TEXT NOT NULL,
                    compose_cc TEXT NOT NULL,
                    compose_bcc TEXT NOT NULL,
                    compose_subject TEXT NOT NULL,
                    is_new_email INTEGER NOT NULL CHECK (is_new_email IN (0, 1)),
                    updated_at INTEGER NOT NULL,
                    original_generated_draft TEXT,
                    provenance_json TEXT,
                    versions_json TEXT,
                    active_version_id TEXT
                )
                """,
            )
            runCatching { s.execute("ALTER TABLE conversation_drafts ADD COLUMN original_generated_draft TEXT") }
            runCatching { s.execute("ALTER TABLE conversation_drafts ADD COLUMN provenance_json TEXT") }
            runCatching { s.execute("ALTER TABLE conversation_drafts ADD COLUMN versions_json TEXT") }
            runCatching { s.execute("ALTER TABLE conversation_drafts ADD COLUMN active_version_id TEXT") }
        }
    }

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }

    @Synchronized
    override fun snapshot(): ActionState = ActionState()

    @Synchronized
    override fun transaction(update: (ActionState) -> ActionState): ActionState = update(ActionState())

    @Synchronized
    override fun recoverInterrupted(controller: ActionController): ActionState = ActionState()

    @Synchronized
    override fun saveDraft(draft: ConversationDraft) {
        val sql = """
            INSERT OR REPLACE INTO conversation_drafts (
                conversation_key, account_id, body, compose_to, compose_cc, compose_bcc,
                compose_subject, is_new_email, updated_at, original_generated_draft, provenance_json, versions_json,
                active_version_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        connection.prepareStatement(sql).use { s ->
            s.setString(1, draft.conversationKey)
            s.setString(2, draft.accountId)
            s.setString(3, draft.body)
            s.setString(4, draft.to)
            s.setString(5, draft.cc)
            s.setString(6, draft.bcc)
            s.setString(7, draft.subject)
            s.setInt(8, if (draft.isNewEmail) 1 else 0)
            s.setLong(9, draft.updatedAtMillis)
            s.setString(10, draft.originalGeneratedDraft)
            s.setString(11, draft.provenance?.let { DraftProvenanceSerializer.serialize(it) })
            s.setString(12, draft.versions.takeIf { it.isNotEmpty() }?.let { DraftVersionSerializer.serializeList(it) })
            s.setString(13, draft.activeVersionId)
            s.executeUpdate()
        }
    }

    @Synchronized
    override fun loadDraft(conversationKey: String): ConversationDraft? {
        val sql = "SELECT * FROM conversation_drafts WHERE conversation_key = ?"
        return connection.prepareStatement(sql).use { s ->
            s.setString(1, conversationKey)
            s.executeQuery().use { rs ->
                if (!rs.next()) null else rs.toDraft()
            }
        }
    }

    @Synchronized
    override fun deleteDraft(conversationKey: String) {
        val sql = "DELETE FROM conversation_drafts WHERE conversation_key = ?"
        connection.prepareStatement(sql).use { s ->
            s.setString(1, conversationKey)
            s.executeUpdate()
        }
    }

    @Synchronized
    override fun listDrafts(): List<ConversationDraft> {
        val sql = "SELECT * FROM conversation_drafts ORDER BY updated_at DESC"
        return connection.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(rs.toDraft())
                }
            }
        }
    }

    private fun ResultSet.toDraft(): ConversationDraft {
        val bodyText = getString("body")
        val prov = getString("provenance_json")?.let { DraftProvenanceSerializer.deserialize(it) }
        val origAi = getString("original_generated_draft")
        val vers = getString("versions_json")?.let { DraftVersionSerializer.deserializeList(it) } ?: emptyList()
        val effectiveVersions = if (vers.isNotEmpty()) vers
        else if (bodyText.isNotBlank()) listOf(DraftVersion(text = bodyText, provenance = prov, originalAiText = origAi))
        else emptyList()

        return ConversationDraft(
            conversationKey = getString("conversation_key"),
            accountId = getString("account_id"),
            body = bodyText,
            to = getString("compose_to"),
            cc = getString("compose_cc"),
            bcc = getString("compose_bcc"),
            subject = getString("compose_subject"),
            isNewEmail = getInt("is_new_email") != 0,
            originalGeneratedDraft = origAi,
            updatedAtMillis = getLong("updated_at"),
            provenance = prov,
            versions = effectiveVersions,
            activeVersionId = getString("active_version_id"),
        )
    }
}
