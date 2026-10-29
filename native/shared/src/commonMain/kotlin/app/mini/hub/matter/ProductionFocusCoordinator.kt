package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.ObservationStore

/**
 * Production Focus Coordinator that bridges observation/source lifecycle events
 * to MatterStore invalidation and ticket-bound upserts.
 */
data class ReconcileBatchResult(
    val processedCount: Int,
    val failureClassName: String?,
)

class ProductionFocusCoordinator(
    private val matterStore: MatterStore,
    private val observationStore: ObservationStore,
) {
    /**
     * Issues an authorization consent ticket before starting matter background work.
     * Throws if the account does not have active, unexcluded Focus consent.
     */
    fun startWork(accountId: AccountId): ConsentTicket {
        return matterStore.issueConsentTicket(accountId)
    }

    /**
     * Commits a matter record using the ticket captured when work began.
     * Rejects stale or revoked tickets, or matters whose source observations are deleted/removed/edited.
     */
    fun commitMatter(matter: MatterRecord, ticket: ConsentTicket): MatterRecord {
        matter.evidenceLinks.forEach { link ->
            val obs = observationStore.getObservation(link.observationId)
                ?: observationStore.getObservationByNotificationKeyIncludingRemoved(link.observationId.key)
            require(obs != null && !obs.isRemoved) {
                "Cannot commit matter: source observation ${link.observationId} was removed or deleted"
            }
        }
        return matterStore.upsertWithTicket(matter, ticket)
    }

    /**
     * Reconciles matter state when an observation source is API-deleted (distinguished from notification dismissal).
     * Immediately invalidates dependent matter evidence and removes matters stripped of all evidence.
     */
    fun reconcileObservationRemoval(observationId: ScopedId) {
        matterStore.invalidateForObservation(observationId)
    }

    /**
     * Reconciles notification dismissal vs provider deletion.
     * Notification dismissal (Android listener dismissal) preserves history and focus evidence unless explicitly API-deleted.
     */
    fun reconcileNotificationDismissal(notificationKey: String) {
        // Notification dismissal preserves active focus matters unless provider-deleted.
    }

    /**
     * Reconciles account-level Focus revocation.
     */
    fun reconcileAccountRevocation(accountId: AccountId) {
        matterStore.invalidateForAccount(accountId)
    }

    /**
     * Repeat-safe recovery worker with bounded event paging: polls pending invalidations from observationStore,
     * processes cleanup reconciliation across matters, and acknowledges events atomically upon successful completion.
     * Safe to call on runtime startup and after newly committed deletion events.
     */
    fun resumePendingReconciliation(accountId: AccountId? = null, batchSize: Int = 100): ReconcileBatchResult {
        val events = observationStore.listPendingInvalidations(accountId, batchSize)
        if (events.isEmpty()) return ReconcileBatchResult(0, null)

        val processedIds = mutableListOf<Long>()
        var failureClassName: String? = null
        for (event in events) {
            try {
                matterStore.invalidateForObservation(event.observationId)
                processedIds.add(event.eventId)
            } catch (e: Throwable) {
                failureClassName = e::class.simpleName ?: "UnknownException"
                break
            }
        }

        if (processedIds.isNotEmpty()) {
            observationStore.acknowledgeInvalidations(processedIds)
        }
        return ReconcileBatchResult(processedIds.size, failureClassName)
    }
}


