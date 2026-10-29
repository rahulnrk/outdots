package app.mini.hub.matter

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant

/**
 * Shared validator for matter and evidence account boundaries and required ID kinds.
 */
object MatterValidation {
    fun validateMatter(matter: MatterRecord, consentGranted: Boolean, excluded: Boolean) {
        require(matter.title.isNotBlank()) { "Matter title must not be blank" }
        require(matter.id.kind == ScopedId.Kind.ACTION_ITEM || matter.id.kind == ScopedId.Kind.FACT) {
            "Matter ID must use a valid domain kind (ACTION_ITEM or FACT)"
        }
        require(matter.id.account == matter.accountId) {
            "Matter ID account must match declared accountId"
        }
        matter.evidenceLinks.forEach { link ->
            require(link.observationId.kind == ScopedId.Kind.OBSERVATION) {
                "Evidence link ID must be of kind OBSERVATION"
            }
            require(link.observationId.account == matter.accountId || link.observationId.account == matter.id.account) {
                "Evidence observation ID account must match matter accountId"
            }
        }
        require(consentGranted && !excluded) {
            "Matter requires explicit active account-level Focus consent and cannot be excluded"
        }
        require(matter.consentGranted && !matter.excluded) {
            "Matter record consent/exclusion does not match store state"
        }
    }
}
