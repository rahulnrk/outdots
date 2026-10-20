package app.mini.hub.android.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mini.hub.android.MainActivity
import app.mini.hub.android.MiniHubRuntime
import app.mini.hub.action.*
import app.mini.hub.attention.*
import app.mini.hub.domain.*
import app.mini.hub.observation.*
import app.mini.hub.persistence.*
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidRealMainActivityInstrumentationTest {
    @Test
    fun realMainActivityLaunchesAndSurvivesRecreationPreservingDrafts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
        val account = AccountId(Provider.WHATSAPP, "synthetic-activity")
        val conversation = ScopedId(account, ScopedId.Kind.CONVERSATION, java.util.UUID.randomUUID().toString())
        val summary = ConversationObservationSummary(conversation, account, "Synthetic", "WhatsApp", "Synthetic",
            "com.whatsapp", "Synthetic", NotificationCategory.DirectMessage,
            ConversationCoverage.NotificationOnly, "Synthetic fixture", UtcInstant(1000), 1, 1, false)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            fun readyViewModel(): InboxViewModel {
                val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    var ready: InboxViewModel? = null
                    scenario.onActivity { activity ->
                        ready = runCatching { MainActivity::class.java.getDeclaredField("viewModel")
                            .apply { isAccessible = true }.get(activity) as? InboxViewModel }.getOrNull()
                    }
                    ready?.let { return it }
                    Thread.sleep(50)
                }
                error("MainActivity did not finish storage bootstrap within test deadline")
            }
            val first = readyViewModel()
            val observation = NotificationNormalizer(IdGenerator { "synthetic-recreation" }, DigestProvider { it })
                .normalize(NotificationRawPayload(key = "synthetic-activity-${conversation.value}", packageId = "com.whatsapp",
                    notificationId = 1, postTimeMillis = 1000L, senderHint = "Synthetic", title = "Synthetic",
                    text = "Synthetic fixture", hasMessagingStyle = true), UtcInstant(1000))
                .copy(id = ScopedId(account, ScopedId.Kind.OBSERVATION, conversation.value), accountId = account,
                    conversationId = conversation)
            assertTrue(MiniHubRuntime.observationStore.persistObservation(observation).isSuccess)
            scenario.onActivity {
                first.selectConversation(summary)
                first.updateLocalDraftText("Synthetic recreation draft")
            }
            val drafts = MiniHubRuntime.actionStore as ConversationDraftStore
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (drafts.loadDraft(conversation.key)?.body != "Synthetic recreation draft" &&
                android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            assertEquals("Synthetic recreation draft", drafts.loadDraft(conversation.key)?.body)
            scenario.recreate()
            val recreated = readyViewModel()
            assertSame(first, recreated, "recreation must retain the ViewModel")
            scenario.onActivity {
                assertEquals(conversation, recreated.state.value.selectedConversation?.conversationId)
                assertEquals("Synthetic recreation draft", recreated.state.value.localDraftText)
            }
        }
        (MiniHubRuntime.actionStore as ConversationDraftStore).deleteDraft(conversation.key)
        val db = MiniHubRuntime.observationStore.javaClass.getDeclaredMethod("getDatabase")
            .apply { isAccessible = true }.invoke(MiniHubRuntime.observationStore) as net.zetetic.database.sqlcipher.SQLiteDatabase
        // Only this test's uniquely scoped synthetic fixture is removed.
        db.execSQL("DELETE FROM obs_fts WHERE conversation_key = ?", arrayOf(conversation.key))
        db.execSQL("DELETE FROM notification_observations WHERE conversation_key = ?", arrayOf(conversation.key))
        db.execSQL("DELETE FROM conversation_summaries WHERE conversation_key = ?", arrayOf(conversation.key))
    }
}
