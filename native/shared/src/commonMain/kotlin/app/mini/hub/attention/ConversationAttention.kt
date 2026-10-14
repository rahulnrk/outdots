package app.mini.hub.attention

import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationObservationSummary

/** User-chosen importance. Never inferred by a model in this milestone. */
enum class ConversationPriority { Normal, Important }

/** User-controlled follow-up state for one conversation. */
enum class FollowUpStatus { NeedsAttention, Waiting, Snoozed, Done }

/** Why MINI moved a conversation back to [FollowUpStatus.NeedsAttention] without a user tap. */
enum class ResurfaceReason { NewActivityWhileWaiting, NewActivityAfterDone, SnoozeDue }

/**
 * Attention state for exactly one account-scoped conversation.
 *
 * Identity is [conversationId], whose key contains provider and local account. Two conversations
 * whose contacts share a display name therefore never share a record.
 *
 * [acknowledgedActivityAt] is the latest conversation activity the user's current choice already
 * accounts for. Activity newer than it is "new activity" for the transition rules.
 */
data class ConversationAttention(
    val conversationId: ScopedId,
    val priority: ConversationPriority = ConversationPriority.Normal,
    val status: FollowUpStatus = FollowUpStatus.NeedsAttention,
    val snoozedUntil: UtcInstant? = null,
    val acknowledgedActivityAt: UtcInstant? = null,
    val resurfacedFrom: FollowUpStatus? = null,
    val resurfaceReason: ResurfaceReason? = null,
    /** New activity arrived while snoozed. The snooze is kept; this is shown and reported when due. */
    val activityWhileSnoozed: Boolean = false,
    val updatedAt: UtcInstant,
    /** False until the user makes any explicit choice for this conversation. */
    val isUserSet: Boolean = false,
) {
    init {
        require(conversationId.kind == ScopedId.Kind.CONVERSATION) { "attention requires a CONVERSATION id" }
        require((status == FollowUpStatus.Snoozed) == (snoozedUntil != null)) { "snoozedUntil is set only while Snoozed" }
        require((resurfacedFrom == null) == (resurfaceReason == null)) { "resurfacedFrom and resurfaceReason go together" }
        require(resurfacedFrom == null || status == FollowUpStatus.NeedsAttention) { "only NeedsAttention can be resurfaced" }
        require(!activityWhileSnoozed || status == FollowUpStatus.Snoozed || resurfaceReason == ResurfaceReason.SnoozeDue) {
            "activityWhileSnoozed only applies to snoozed or snooze-due conversations"
        }
    }

    val key: String get() = conversationId.key
}

/** An explicit user action. AI never produces these in this milestone. */
sealed class AttentionAction {
    data class SetPriority(val priority: ConversationPriority) : AttentionAction()
    data object MarkNeedsAttention : AttentionAction()
    data object MarkWaiting : AttentionAction()
    data object MarkDone : AttentionAction()
    data class Snooze(val until: UtcInstant) : AttentionAction()
}

sealed class AttentionDecision {
    data class Applied(val record: ConversationAttention) : AttentionDecision()
    data class Rejected(val reason: String) : AttentionDecision()
}

/**
 * Platform-neutral follow-up rules. Pure functions of (record, latest activity, now) so Android
 * and future iOS adapters produce identical transitions.
 *
 * Transition rules:
 * - No record: Normal priority, NeedsAttention.
 * - Priority changes never alter follow-up status, and automatic transitions never alter priority.
 * - Any user follow-up choice acknowledges all activity seen so far and clears resurfacing markers.
 * - Waiting or Done + activity newer than acknowledged: NeedsAttention, recording the previous
 *   choice and reason so the UI can say why it reappeared. Nothing is silently discarded.
 * - Snoozed + new activity before the snooze is due: stays Snoozed (the user chose a time) and is
 *   flagged [ConversationAttention.activityWhileSnoozed]; it is visibly marked in the inbox.
 * - Snoozed and now >= snoozedUntil: NeedsAttention with reason SnoozeDue, evaluated whenever
 *   MINI opens or refreshes. There is no background reminder in this milestone.
 * - Reconciliation is idempotent: applying it again with the same inputs changes nothing.
 * - Native handoff or approval never changes attention. Only the user marks Done.
 */
object AttentionRules {
    fun default(conversationId: ScopedId, now: UtcInstant): ConversationAttention =
        ConversationAttention(conversationId = conversationId, updatedAt = now)

    fun hasNewActivity(record: ConversationAttention, latestActivityAt: UtcInstant?): Boolean {
        if (latestActivityAt == null) return false
        val acknowledged = record.acknowledgedActivityAt ?: return true
        return latestActivityAt > acknowledged
    }

    /** Applies automatic transitions. Returns the same instance when nothing changes. */
    fun reconcile(
        record: ConversationAttention,
        latestActivityAt: UtcInstant?,
        now: UtcInstant,
        resurfaceOnNewActivity: Boolean = true,
    ): ConversationAttention {
        val newActivity = resurfaceOnNewActivity && hasNewActivity(record, latestActivityAt)
        return when (record.status) {
            FollowUpStatus.NeedsAttention -> record
            FollowUpStatus.Waiting -> if (!newActivity) record else record.copy(
                status = FollowUpStatus.NeedsAttention,
                resurfacedFrom = FollowUpStatus.Waiting,
                resurfaceReason = ResurfaceReason.NewActivityWhileWaiting,
                updatedAt = now,
            )
            FollowUpStatus.Done -> if (!newActivity) record else record.copy(
                status = FollowUpStatus.NeedsAttention,
                resurfacedFrom = FollowUpStatus.Done,
                resurfaceReason = ResurfaceReason.NewActivityAfterDone,
                updatedAt = now,
            )
            FollowUpStatus.Snoozed -> {
                val until = requireNotNull(record.snoozedUntil)
                val sawActivity = record.activityWhileSnoozed || newActivity
                when {
                    now >= until -> record.copy(
                        status = FollowUpStatus.NeedsAttention,
                        snoozedUntil = null,
                        resurfacedFrom = FollowUpStatus.Snoozed,
                        resurfaceReason = ResurfaceReason.SnoozeDue,
                        activityWhileSnoozed = sawActivity,
                        updatedAt = now,
                    )
                    newActivity && !record.activityWhileSnoozed -> record.copy(activityWhileSnoozed = true, updatedAt = now)
                    else -> record
                }
            }
        }
    }

    fun apply(
        record: ConversationAttention,
        action: AttentionAction,
        latestActivityAt: UtcInstant?,
        now: UtcInstant,
    ): AttentionDecision {
        val acknowledged = maxOf(record.acknowledgedActivityAt, latestActivityAt)
        fun followUp(status: FollowUpStatus, until: UtcInstant? = null) = AttentionDecision.Applied(
            record.copy(
                status = status,
                snoozedUntil = until,
                acknowledgedActivityAt = acknowledged,
                resurfacedFrom = null,
                resurfaceReason = null,
                activityWhileSnoozed = false,
                updatedAt = now,
                isUserSet = true,
            ),
        )
        return when (action) {
            is AttentionAction.SetPriority -> AttentionDecision.Applied(
                record.copy(priority = action.priority, updatedAt = now, isUserSet = true),
            )
            AttentionAction.MarkNeedsAttention -> followUp(FollowUpStatus.NeedsAttention)
            AttentionAction.MarkWaiting -> followUp(FollowUpStatus.Waiting)
            AttentionAction.MarkDone -> followUp(FollowUpStatus.Done)
            is AttentionAction.Snooze ->
                if (action.until <= now) AttentionDecision.Rejected("Snooze time must be in the future.")
                else followUp(FollowUpStatus.Snoozed, action.until)
        }
    }

    private fun maxOf(a: UtcInstant?, b: UtcInstant?): UtcInstant? = when {
        a == null -> b
        b == null -> a
        else -> if (a >= b) a else b
    }
}

/** One inbox row with its attention record. */
data class AttentionEntry(
    val summary: ConversationObservationSummary,
    val attention: ConversationAttention,
)

/** Attention view grouping. Done conversations remain available in archived history. */
data class AttentionView(
    val needsAttention: List<AttentionEntry>,
    val waiting: List<AttentionEntry>,
    val snoozed: List<AttentionEntry>,
    val done: List<AttentionEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = needsAttention.isEmpty() && waiting.isEmpty() && snoozed.isEmpty()

    companion object {
        /** Important first, then most recent activity. Snoozed by soonest due. */
        fun build(entries: List<AttentionEntry>): AttentionView {
            val byRecency = compareByDescending<AttentionEntry> { it.summary.latestEventAt.epochMillis }
                .thenBy { it.attention.key }
            return AttentionView(
                needsAttention = entries.filter { it.attention.status == FollowUpStatus.NeedsAttention }
                    .sortedWith(
                        compareBy<AttentionEntry> { if (it.attention.priority == ConversationPriority.Important) 0 else 1 }
                            .then(byRecency),
                    ),
                waiting = entries.filter { it.attention.status == FollowUpStatus.Waiting }
                    .sortedWith(
                        compareBy<AttentionEntry> { if (it.attention.priority == ConversationPriority.Important) 0 else 1 }
                            .then(byRecency),
                    ),
                snoozed = entries.filter { it.attention.status == FollowUpStatus.Snoozed }
                    .sortedWith(compareBy<AttentionEntry> { it.attention.snoozedUntil?.epochMillis ?: Long.MAX_VALUE }.then(byRecency)),
                done = entries.filter { it.attention.status == FollowUpStatus.Done }
                    .sortedWith(byRecency),
            )
        }
    }
}
