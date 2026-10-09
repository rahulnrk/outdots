package app.mini.hub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.observation.CommunicationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunicationSourcePreferencesDeviceTest {
    @Test
    fun selectedSourcesSurviveASecondPreferencesInstance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = CommunicationSourcePreferences(context)
        first.replaceSelection(setOf(CommunicationSource.Gmail, CommunicationSource.WhatsApp))

        val afterProcessRestart = CommunicationSourcePreferences(context)
        assertEquals(
            setOf(CommunicationSource.Gmail, CommunicationSource.WhatsApp),
            afterProcessRestart.selectedSources(),
        )

        afterProcessRestart.replaceSelection(CommunicationSource.defaultSelection)
    }
}
