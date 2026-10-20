package app.mini.hub.android.connector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.observation.ObservationProvenance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstagramReadMappingDeviceTest {
    @Test
    fun boundedPayloadPreservesIdsDirectionAndPartialCoverage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ingestor = InstagramReadIngestor(context)
        val payload = JSONObject()
            .put("verified_user_id", "owner-1")
            .put("has_more_threads", true)
            .put("conversations", JSONArray().put(
                JSONObject()
                    .put("id", "thread-1")
                    .put("title", "Synthetic thread")
                    .put("has_more_messages", true)
                    .put("messages", JSONArray().put(
                        JSONObject()
                            .put("id", "message-1")
                            .put("timestamp_ms", 1700000000000L)
                            .put("text", "synthetic body")
                            .put("sender_id", "sender-1")
                            .put("direction", "inbound"),
                    )),
            ))
        val account = AccountId(Provider.INSTAGRAM, "owner-1")
        val view = ingestor.parseReadJsonForTest(payload.toString())
        assertTrue(view.partial)
        val conversation = view.conversations.single()
        val message = conversation.messages.single()
        val observation = ingestor.mapForTest(account, conversation, message)
        assertEquals("thread-1", observation.conversationId.value)
        assertEquals("message-1", observation.id.value)
        assertEquals("sender-1", observation.senderHint)
        assertEquals("Inbound", observation.direction?.name)
        assertEquals(ObservationProvenance.ThirdPartyBridge, observation.provenance)
        assertEquals(observation.notificationKey, ingestor.mapForTest(account, conversation, message).notificationKey)
    }

    @Test
    fun unknownAccountCannotUseAnotherAccountSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = InstagramReadIngestor(context).sync(AccountId(Provider.INSTAGRAM, "synthetic-missing-account"))
        assertEquals(InstagramReadIngestor.Result.Failed("session_expired"), result)
    }
}
