package app.mini.hub

/** Identifies the running platform for diagnostics. Contains no private data. */
expect fun platformName(): String

object SharedCore {
    const val ROOT_PACKAGE = "app.mini.hub"

    /** Reserved for Agent C (local intelligence, Resource Supervisor). Agent B does not edit it. */
    const val INTELLIGENCE_PACKAGE = "app.mini.hub.intelligence"
}
