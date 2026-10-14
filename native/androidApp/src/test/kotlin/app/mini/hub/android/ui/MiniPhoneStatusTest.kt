package app.mini.hub.android.ui

import app.mini.hub.action.ActionRoute
import app.mini.hub.action.OutboxState
import app.mini.hub.action.ReviewedActionSnapshot
import app.mini.hub.domain.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPhoneStatusTest {
    @Test
    fun listenerDisconnectIsNotAccountSetup() {
        val s = miniPhoneStatus(permissionGranted = true, listenerConnected = false, accountCount = 3, accountsNeedingAttention = 0)
        assertEquals(MiniPhoneStatus.ListenerReconnecting, s)
        assertEquals("Reconnecting", s.label)
    }

    @Test
    fun permissionOffIsReportedSeparately() {
        assertEquals(
            MiniPhoneStatus.NotificationAccessNeeded,
            miniPhoneStatus(permissionGranted = false, listenerConnected = false, accountCount = 2, accountsNeedingAttention = 0),
        )
    }

    @Test
    fun unhealthyAccountWinsOverListener() {
        assertEquals(
            MiniPhoneStatus.AccountsNeedAttention,
            miniPhoneStatus(permissionGranted = true, listenerConnected = false, accountCount = 2, accountsNeedingAttention = 1),
        )
    }

    @Test
    fun onlyNoAccountsReadsAsNeedsSetup() {
        assertEquals(MiniPhoneStatus.NoAccounts, miniPhoneStatus(true, true, 0, 0))
        assertEquals(MiniPhoneStatus.OnThisPhone, miniPhoneStatus(true, true, 3, 0))
    }

    private fun snap(id: String, state: OutboxState) = ReviewedActionSnapshot(
        commandId = id,
        accountId = "gmail:a",
        conversationId = "c",
        route = ActionRoute.NativeHandoff,
        recipients = listOf("x@example.com"),
        cc = emptyList(),
        bcc = emptyList(),
        subject = null,
        body = "b",
        attachmentNames = emptyList(),
        replyTarget = null,
        state = state,
        createdAt = UtcInstant(1L),
    )

    @Test
    fun historyPutsUncertainOutcomesFirstAndDropsSettled() {
        val ordered = outcomeHistoryOrder(
            listOf(
                snap("h1", OutboxState.HandoffOpened),
                snap("u1", OutboxState.OutcomeUnknown),
                snap("r1", OutboxState.Rejected),
                snap("h2", OutboxState.HandoffOpened),
            ),
        )
        assertEquals(listOf("u1", "r1", "h2", "h1"), ordered.map { it.commandId })
    }
}
