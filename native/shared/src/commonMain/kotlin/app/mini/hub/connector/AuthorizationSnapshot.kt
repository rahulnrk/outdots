package app.mini.hub.connector

import app.mini.hub.action.ActionRoute

/**
 * Opaque grant/policy strings bound into an exact-review digest. They are derived from the
 * account's current scopes and enabled capabilities so a later revoke invalidates the approval.
 */
object AuthorizationSnapshot {
    fun grantVersion(grantedScopes: Set<String>): String =
        "grants:${grantedScopes.sorted().joinToString("+").ifEmpty { "none" }}"

    fun policyVersion(enabled: Set<Capability>, route: ActionRoute): String {
        val family = if (route == ActionRoute.Api) "api-review" else "notification-handoff"
        val caps = enabled.map { it.name }.sorted().joinToString("+").ifEmpty { "none" }
        return "policy:$family|caps:$caps"
    }
}
