package app.mini.hub.android

import app.mini.hub.attention.AttentionStore
import app.mini.hub.matter.MatterStore
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.ObservationStore

/**
 * Outcome of opening the encrypted stores. There is no memory-only fallback: if any encrypted
 * store cannot be opened the runtime is [Failed], the UI blocks with [Failed.userMessage], and
 * background hosts do nothing.
 */
sealed class RuntimeStorage {
    class Ready(
        val observations: ObservationStore,
        val actions: ActionStore,
        val attention: AttentionStore,
        val matters: MatterStore,
    ) : RuntimeStorage()

    /** [cause] is the exception class name only, for a count-free state log line. */
    class Failed(val userMessage: String, val cause: String) : RuntimeStorage()

    companion object {
        const val USER_MESSAGE =
            "MINI could not open its encrypted storage on this phone, so it has stopped to avoid " +
                "losing your messages. Nothing was saved. Restart the phone and open MINI again. " +
                "If this keeps happening, reinstalling MINI will reset its storage."

        fun open(
            openObservations: () -> ObservationStore,
            openActions: () -> ActionStore,
            openAttention: () -> AttentionStore,
            openMatters: () -> MatterStore,
        ): RuntimeStorage = try {
            Ready(openObservations(), openActions(), openAttention(), openMatters())
        } catch (e: Throwable) {
            Failed(USER_MESSAGE, e.javaClass.simpleName)
        }

        /**
         * Runs [block] only when storage is [Ready]. Otherwise logs one state line with no counts,
         * content or identifiers and returns false, so hosts neither crash nor retry in a loop.
         */
        inline fun runIfReady(state: RuntimeStorage?, work: String, log: (String) -> Unit, block: () -> Unit): Boolean {
            if (state !is Ready) {
                log("work=$work skipped storage=unavailable")
                return false
            }
            block()
            return true
        }
    }
}
