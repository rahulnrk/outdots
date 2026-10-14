package app.mini.hub.persistence

import app.mini.hub.action.ActionController
import app.mini.hub.action.ActionState

/**
 * Durable action state boundary. Implementations must commit approvals and outbox transitions atomically.
 * The production Android implementation must use SQLCipher with an Android Keystore-wrapped key.
 */
interface ActionStore {
    val qualification: StorageQualification
    fun snapshot(): ActionState
    fun transaction(update: (ActionState) -> ActionState): ActionState
    fun recoverInterrupted(controller: ActionController): ActionState
}

enum class StorageQualification {
    InMemoryTestOnly,
    SqlCipherAndroidKeystorePending,
    SqlCipherAndroidKeystoreQualified,
}

/** Editable composer text keyed by conversation. Approvals are never stored here. */
data class ConversationDraft(
    val conversationKey: String,
    val accountId: String,
    val body: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val isNewEmail: Boolean = false,
    val originalGeneratedDraft: String? = null,
    val updatedAtMillis: Long,
)

interface ConversationDraftStore {
    fun saveDraft(draft: ConversationDraft)
    fun loadDraft(conversationKey: String): ConversationDraft?
    fun deleteDraft(conversationKey: String)
    fun listDrafts(): List<ConversationDraft>
}

/**
 * Deterministic test repository. It deliberately advertises that it is not durable or encrypted and must never be
 * wired into a release Android build.
 */
class InMemoryActionStore(initial: ActionState = ActionState()) : ActionStore, ConversationDraftStore {
    private var state = initial
    private val drafts = LinkedHashMap<String, ConversationDraft>()
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    override fun snapshot(): ActionState = state
    override fun transaction(update: (ActionState) -> ActionState): ActionState {
        val next = update(state)
        state = next
        return next
    }
    override fun recoverInterrupted(controller: ActionController): ActionState {
        state = controller.recoverInterrupted(state)
        return state
    }
    override fun saveDraft(draft: ConversationDraft) {
        drafts[draft.conversationKey] = draft
    }
    override fun loadDraft(conversationKey: String): ConversationDraft? = drafts[conversationKey]
    override fun deleteDraft(conversationKey: String) {
        drafts.remove(conversationKey)
    }
    override fun listDrafts(): List<ConversationDraft> = drafts.values.sortedByDescending { it.updatedAtMillis }
}

/** Contract for the Android SQLCipher implementation. No implementation is claimed until key lifecycle and migration tests pass. */
interface EncryptedActionStore : ActionStore, ConversationDraftStore {
    override val qualification: StorageQualification
        get() = StorageQualification.SqlCipherAndroidKeystorePending
}
