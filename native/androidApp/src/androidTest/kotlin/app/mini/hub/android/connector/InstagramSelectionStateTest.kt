package app.mini.hub.android.connector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSelectionStateTest {
    @Test
    fun syntheticChoicesKeepLabelsAndSubmitExactUpstreamValue() {
        val snapshot = JSONObject()
            .put("state", "select")
            .put("choices", JSONArray()
                .put(JSONObject().put("field_id", "choice").put("value", "profile-value-a").put("label", "Profile A"))
                .put(JSONObject().put("field_id", "choice").put("value", "mfa-sms-value").put("label", "Text message")))
        val choices = parseInstagramSelectionChoices(snapshot)
        assertEquals(listOf("Profile A", "Text message"), choices.map { it.label })
        assertEquals(listOf("profile-value-a", "mfa-sms-value"), choices.map { it.value })
        val state = InstagramLoginUiState(
            status = InstagramLoginStatus.INPUT,
            state = "select",
            instructions = null,
            failureCode = null,
            fieldIds = listOf("choice"),
            choices = choices,
        )
        assertTrue(isValidInstagramSelection(state, "choice", "mfa-sms-value"))
        assertFalse(isValidInstagramSelection(state, "choice", "Profile A"))
        assertFalse(isValidInstagramSelection(state, "choice", ""))
    }
}
