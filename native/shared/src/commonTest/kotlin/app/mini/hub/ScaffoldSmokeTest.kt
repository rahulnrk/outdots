package app.mini.hub

import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldSmokeTest {
    @Test
    fun reservesIntelligencePackageForAgentC() {
        assertEquals("app.mini.hub.intelligence", SharedCore.INTELLIGENCE_PACKAGE)
    }
}
