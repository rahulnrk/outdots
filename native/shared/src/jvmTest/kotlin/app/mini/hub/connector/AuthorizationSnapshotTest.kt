package app.mini.hub.connector

import app.mini.hub.action.ActionRoute
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthorizationSnapshotTest {
    @Test(timeout = 10_000)
    fun grantVersionTracksSortedScopesAndTreatsEmptyAsNone() {
        assertEquals("grants:none", AuthorizationSnapshot.grantVersion(emptySet()))
        assertEquals(
            "grants:https://www.googleapis.com/auth/gmail.readonly+https://www.googleapis.com/auth/gmail.send",
            AuthorizationSnapshot.grantVersion(
                setOf(
                    "https://www.googleapis.com/auth/gmail.send",
                    "https://www.googleapis.com/auth/gmail.readonly",
                ),
            ),
        )
    }

    @Test(timeout = 10_000)
    fun losingSendScopeChangesGrantVersion() {
        val withSend = AuthorizationSnapshot.grantVersion(
            setOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send"),
        )
        val withoutSend = AuthorizationSnapshot.grantVersion(setOf("https://www.googleapis.com/auth/gmail.readonly"))
        assertNotEquals(withSend, withoutSend)
    }

    @Test(timeout = 10_000)
    fun disablingReplyCapabilityChangesPolicyVersion() {
        val allowed = AuthorizationSnapshot.policyVersion(setOf(Capability.Reply, Capability.ReadBody), ActionRoute.Api)
        val blocked = AuthorizationSnapshot.policyVersion(setOf(Capability.ReadBody), ActionRoute.Api)
        assertEquals("policy:api-review|caps:ReadBody+Reply", allowed)
        assertEquals("policy:api-review|caps:ReadBody", blocked)
        assertEquals(
            "policy:notification-handoff|caps:none",
            AuthorizationSnapshot.policyVersion(emptySet(), ActionRoute.NativeHandoff),
        )
    }
}
