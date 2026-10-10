package app.mini.hub.understanding

import app.mini.hub.attention.ConversationAttention
import app.mini.hub.attention.FollowUpStatus
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.ObservedNotification

/** Deterministic, on-device message understanding used for inbox explanations and draft context. */
data class MessageUnderstanding(
    val purpose: String,
    val urgency: String,
    val deadline: String?,
    val requiredAction: String?,
    val unansweredRequest: Boolean,
    val waiting: Boolean,
    val importantChange: Boolean,
    val explanation: String,
    val evidenceText: String,
) {
    val hasSignal: Boolean
        get() = deadline != null || requiredAction != null || unansweredRequest || waiting || importantChange || urgency != "Routine"
}

object MessageUnderstandingEngine {
    fun analyze(
        summary: ConversationObservationSummary,
        observations: List<ObservedNotification>,
        attention: ConversationAttention,
    ): MessageUnderstanding {
        val latest = observations.maxByOrNull { it.observedAt.epochMillis }
        val text = listOfNotNull(latest?.title, latest?.textFragments?.joinToString(" "), summary.latestSnippet)
            .joinToString(" ").trim()
        val lower = text.lowercase()
        val deadline = deadlinePhrase(text)
        val action = when {
            lower.containsAny("please ", "can you ", "could you ", "need you to", "action required", "follow up") ->
                "Review and respond to the request"
            lower.containsAny("approve", "confirm", "sign ", "send ", "submit ", "pay ") ->
                "Complete the requested action"
            else -> null
        }
        val purpose = when {
            lower.containsAny("invoice", "payment", "pay ", "receipt") -> "Finance or payment"
            lower.containsAny("meeting", "calendar", "schedule", "interview", "call") -> "Scheduling"
            lower.containsAny("error", "failed", "outage", "urgent", "asap") -> "Problem or escalation"
            lower.containsAny("please ", "can you ", "could you ", "need you") -> "Request"
            else -> "Conversation update"
        }
        val urgency = when {
            lower.containsAny("urgent", "asap", "immediately", "today", "overdue", "deadline") -> "High"
            deadline != null || lower.containsAny("soon", "this week", "tomorrow") -> "Time-sensitive"
            else -> "Routine"
        }
        val importantChange = lower.containsAny("updated", "changed", "rescheduled", "cancelled", "canceled", "new terms")
        val visibleAction = action.takeIf { attention.status != FollowUpStatus.Done }
        val unanswered = visibleAction != null && attention.status == FollowUpStatus.NeedsAttention
        val waiting = attention.status == FollowUpStatus.Waiting
        val reasons = buildList {
            deadline?.let { add("deadline mentioned: $it") }
            visibleAction?.let { add(it.lowercase()) }
            if (unanswered) add("request has not been marked complete")
            if (waiting) add("you marked this conversation as waiting")
            if (importantChange) add("message contains a possible change")
            if (isEmpty()) add("no strong action or timing signal detected")
        }
        return MessageUnderstanding(
            purpose = purpose,
            urgency = urgency,
            deadline = deadline,
            requiredAction = visibleAction,
            unansweredRequest = unanswered,
            waiting = waiting,
            importantChange = importantChange,
            explanation = reasons.joinToString("; "),
            evidenceText = text.take(240),
        )
    }

    private fun deadlinePhrase(text: String): String? {
        val match = Regex("(?i)\\b(by|before|due|deadline|on)\\s+(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{1,2}(?:st|nd|rd|th)?(?:[ /-]\\d{1,2})?)\\b")
            .find(text)
        return match?.value
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}
