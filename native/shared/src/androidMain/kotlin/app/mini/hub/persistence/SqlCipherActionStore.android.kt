package app.mini.hub.persistence

import android.content.Context
import android.content.ContentValues
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionRoute
import app.mini.hub.action.ActionState
import app.mini.hub.action.ApprovalRecord
import app.mini.hub.action.OutboxCommand
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ReviewedActionSnapshot
import app.mini.hub.domain.UtcInstant
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Android production store for the exact-action state machine.
 *
 * The SQLCipher database key is random and never stored in plaintext. It is wrapped by an
 * AES-GCM key held by Android Keystore. A damaged key envelope fails closed instead of silently
 * creating a new database and losing pending approvals.
 */
class SqlCipherActionStore(
    context: Context,
    databaseName: String = "mini-hub-actions.db",
) : EncryptedActionStore {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val keyEnvelope = KeystoreDatabaseKey(appContext, databaseName)

    // Device migration, key invalidation and restart tests are still required before qualification.
    override val qualification: StorageQualification = StorageQualification.SqlCipherAndroidKeystorePending

    private val database: SQLiteDatabase by lazy {
        databaseFile.parentFile?.mkdirs()
        SqlCipherRuntime.ensureLoaded()
        val db = SQLiteDatabase.openOrCreateDatabase(
            databaseFile,
            keyEnvelope.databaseKey(),
            null,
            null,
            null,
        )
        db.execSQL(SCHEMA_APPROVALS)
        db.execSQL(SCHEMA_OUTBOX)
        db.execSQL(SCHEMA_SNAPSHOTS)
        db.execSQL(SCHEMA_DRAFTS)
        runCatching { db.execSQL("ALTER TABLE conversation_drafts ADD COLUMN original_generated_draft TEXT") }
        db
    }

    init {
        database
    }

    @Synchronized
    override fun snapshot(): ActionState = readState(database)

    @Synchronized
    override fun transaction(update: (ActionState) -> ActionState): ActionState {
        database.beginTransaction()
        try {
            val next = update(readState(database))
            replaceState(database, next)
            database.setTransactionSuccessful()
            return next
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    override fun recoverInterrupted(controller: ActionController): ActionState = transaction(controller::recoverInterrupted)

    private fun readState(database: SQLiteDatabase): ActionState {
        val approvals = database.query(
            "approvals",
            APPROVAL_COLUMNS,
            null,
            null,
            null,
            null,
            "approved_at ASC, id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ApprovalRecord(
                            id = cursor.text("id"),
                            payloadDigest = cursor.text("payload_digest"),
                            approvedAt = UtcInstant(cursor.long("approved_at")),
                            expiresAt = UtcInstant(cursor.long("expires_at")),
                            consumed = cursor.int("consumed") != 0,
                        ),
                    )
                }
            }
        }
        val outbox = database.query(
            "outbox",
            OUTBOX_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at ASC, id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OutboxCommand(
                            id = cursor.text("id"),
                            approvalId = cursor.text("approval_id"),
                            payloadDigest = cursor.text("payload_digest"),
                            route = ActionRoute.valueOf(cursor.text("route")),
                            state = OutboxState.valueOf(cursor.text("state")),
                            createdAt = UtcInstant(cursor.long("created_at")),
                            lastAttemptAt = cursor.optionalLong("last_attempt_at")?.let(::UtcInstant),
                            receipt = cursor.optionalText("receipt"),
                            failureReason = cursor.optionalText("failure_reason"),
                        ),
                    )
                }
            }
        }
        val snapshots = database.query(
            "reviewed_snapshots",
            SNAPSHOT_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at ASC, command_id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReviewedActionSnapshot(
                            commandId = cursor.text("command_id"),
                            accountId = cursor.text("account_id"),
                            conversationId = cursor.text("conversation_id"),
                            route = ActionRoute.valueOf(cursor.text("route")),
                            recipients = decodeList(cursor.text("recipients")),
                            cc = decodeList(cursor.text("cc")),
                            bcc = decodeList(cursor.text("bcc")),
                            subject = cursor.optionalText("subject"),
                            body = cursor.text("body"),
                            attachmentNames = decodeList(cursor.text("attachment_names")),
                            replyTarget = cursor.optionalText("reply_target"),
                            state = OutboxState.valueOf(cursor.text("state")),
                            createdAt = UtcInstant(cursor.long("created_at")),
                            receipt = cursor.optionalText("receipt"),
                            failureReason = cursor.optionalText("failure_reason"),
                        ),
                    )
                }
            }
        }
        return ActionState(approvals, outbox, snapshots)
    }

    private fun replaceState(database: SQLiteDatabase, state: ActionState) {
        database.delete("outbox", null, null)
        database.delete("approvals", null, null)
        state.approvals.forEach { approval ->
            database.insertOrThrow("approvals", null, ContentValues().apply {
                put("id", approval.id)
                put("payload_digest", approval.payloadDigest)
                put("approved_at", approval.approvedAt.epochMillis)
                put("expires_at", approval.expiresAt.epochMillis)
                put("consumed", if (approval.consumed) 1 else 0)
            })
        }
        state.outbox.forEach { command ->
            database.insertOrThrow("outbox", null, ContentValues().apply {
                put("id", command.id)
                put("approval_id", command.approvalId)
                put("payload_digest", command.payloadDigest)
                put("route", command.route.name)
                put("state", command.state.name)
                put("created_at", command.createdAt.epochMillis)
                command.lastAttemptAt?.let { put("last_attempt_at", it.epochMillis) }
                command.receipt?.let { put("receipt", it) }
                command.failureReason?.let { put("failure_reason", it) }
            })
        }
        database.delete("reviewed_snapshots", null, null)
        state.reviewedSnapshots.forEach { snap ->
            database.insertOrThrow("reviewed_snapshots", null, ContentValues().apply {
                put("command_id", snap.commandId)
                put("account_id", snap.accountId)
                put("conversation_id", snap.conversationId)
                put("route", snap.route.name)
                put("recipients", encodeList(snap.recipients))
                put("cc", encodeList(snap.cc))
                put("bcc", encodeList(snap.bcc))
                snap.subject?.let { put("subject", it) }
                put("body", snap.body)
                put("attachment_names", encodeList(snap.attachmentNames))
                snap.replyTarget?.let { put("reply_target", it) }
                put("state", snap.state.name)
                put("created_at", snap.createdAt.epochMillis)
                snap.receipt?.let { put("receipt", it) }
                snap.failureReason?.let { put("failure_reason", it) }
            })
        }
    }

    @Synchronized
    override fun saveDraft(draft: ConversationDraft) {
        database.insertWithOnConflict(
            "conversation_drafts",
            null,
            ContentValues().apply {
                put("conversation_key", draft.conversationKey)
                put("account_id", draft.accountId)
                put("body", draft.body)
                put("compose_to", draft.to)
                put("compose_cc", draft.cc)
                put("compose_bcc", draft.bcc)
                put("compose_subject", draft.subject)
                put("is_new_email", if (draft.isNewEmail) 1 else 0)
                put("updated_at", draft.updatedAtMillis)
                put("original_generated_draft", draft.originalGeneratedDraft)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun loadDraft(conversationKey: String): ConversationDraft? {
        return database.query(
            "conversation_drafts",
            DRAFT_COLUMNS,
            "conversation_key = ?",
            arrayOf(conversationKey),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.toDraft()
        }
    }

    @Synchronized
    override fun deleteDraft(conversationKey: String) {
        database.delete("conversation_drafts", "conversation_key = ?", arrayOf(conversationKey))
    }

    @Synchronized
    override fun listDrafts(): List<ConversationDraft> {
        return database.query(
            "conversation_drafts",
            DRAFT_COLUMNS,
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toDraft())
            }
        }
    }

    private fun android.database.Cursor.toDraft(): ConversationDraft = ConversationDraft(
        conversationKey = text("conversation_key"),
        accountId = text("account_id"),
        body = text("body"),
        to = text("compose_to"),
        cc = text("compose_cc"),
        bcc = text("compose_bcc"),
        subject = text("compose_subject"),
        isNewEmail = int("is_new_email") != 0,
        originalGeneratedDraft = optionalText("original_generated_draft"),
        updatedAtMillis = long("updated_at"),
    )

    private companion object {
        const val SCHEMA_APPROVALS = """
            CREATE TABLE IF NOT EXISTS approvals (
                id TEXT NOT NULL PRIMARY KEY,
                payload_digest TEXT NOT NULL,
                approved_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                consumed INTEGER NOT NULL CHECK (consumed IN (0, 1))
            )
        """
        const val SCHEMA_OUTBOX = """
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
        """
        val APPROVAL_COLUMNS = arrayOf("id", "payload_digest", "approved_at", "expires_at", "consumed")
        val OUTBOX_COLUMNS = arrayOf("id", "approval_id", "payload_digest", "route", "state", "created_at", "last_attempt_at", "receipt", "failure_reason")
        const val SCHEMA_SNAPSHOTS = """
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
        """
        const val SCHEMA_DRAFTS = """
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
                original_generated_draft TEXT
            )
        """
        val SNAPSHOT_COLUMNS = arrayOf(
            "command_id", "account_id", "conversation_id", "route", "recipients", "cc", "bcc",
            "subject", "body", "attachment_names", "reply_target", "state", "created_at", "receipt", "failure_reason",
        )
        val DRAFT_COLUMNS = arrayOf(
            "conversation_key", "account_id", "body", "compose_to", "compose_cc", "compose_bcc",
            "compose_subject", "is_new_email", "updated_at", "original_generated_draft",
        )
    }
}

private object SqlCipherRuntime {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

private class KeystoreDatabaseKey(
    private val context: Context,
    databaseName: String,
) {
    private val alias = "mini.hub.sqlcipher.${Base64.encodeToString(databaseName.toByteArray(), Base64.NO_WRAP)}"
    private val preferences = context.getSharedPreferences("mini_hub_keystore", Context.MODE_PRIVATE)
    private val ciphertextKey = "$alias.ciphertext"
    private val ivKey = "$alias.iv"

    fun databaseKey(): ByteArray {
        val wrappingKey = wrappingKey()
        val ciphertext = preferences.getString(ciphertextKey, null)
        val iv = preferences.getString(ivKey, null)
        if ((ciphertext == null) != (iv == null)) error("incomplete SQLCipher key envelope")
        if (ciphertext == null || iv == null) {
            val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encrypted = cipher.doFinal(databaseKey)
            check(preferences.edit()
                .putString(ciphertextKey, b64(encrypted))
                .putString(ivKey, b64(cipher.iv))
                .commit()) { "could not persist SQLCipher key envelope" }
            return databaseKey
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        } catch (error: Exception) {
            throw IllegalStateException("SQLCipher key envelope could not be decrypted", error)
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}

private fun encodeList(items: List<String>): String = items.joinToString("\u001e")
private fun decodeList(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split('\u001e')

private fun android.database.Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))
private fun android.database.Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun android.database.Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun android.database.Cursor.optionalText(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
private fun android.database.Cursor.optionalLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
