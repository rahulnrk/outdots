package app.mini.hub.android

import app.mini.hub.action.ActionController
import app.mini.hub.action.OutboxState
import app.mini.hub.persistence.ActionStore

/** Work that must run once per process start, before any UI can dispatch. */
object StartupRecovery {
    /**
     * Any command left in Dispatching was interrupted after its network call may have started.
     * It becomes OutcomeUnknown and is never re-sent automatically. Returns how many were recovered
     * and logs that count only.
     */
    fun recoverOutbox(store: ActionStore, controller: ActionController, log: (String) -> Unit): Int {
        val interrupted = store.snapshot().outbox.count { it.state == OutboxState.Dispatching }
        if (interrupted == 0) return 0
        store.recoverInterrupted(controller)
        log("outbox recovered interrupted=$interrupted state=OutcomeUnknown")
        return interrupted
    }
}
