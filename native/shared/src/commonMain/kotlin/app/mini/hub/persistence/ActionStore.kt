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

/**
 * Deterministic test repository. It deliberately advertises that it is not durable or encrypted and must never be
 * wired into a release Android build.
 */
class InMemoryActionStore(initial: ActionState = ActionState()) : ActionStore {
    private var state = initial
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
}

/** Contract for the Android SQLCipher implementation. No implementation is claimed until key lifecycle and migration tests pass. */
interface EncryptedActionStore : ActionStore {
    override val qualification: StorageQualification
        get() = StorageQualification.SqlCipherAndroidKeystorePending
}
