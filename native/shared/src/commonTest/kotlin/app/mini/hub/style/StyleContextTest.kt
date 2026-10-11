package app.mini.hub.style

import app.mini.hub.connector.AccountType
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectorRoute
import app.mini.hub.connector.ConnectionState
import app.mini.hub.connector.MessageDirection
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.TruncationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StyleContextTest {
    private val now = UtcInstant(1_700_000_000_000L)
    private val account = ConnectedAccount(
        AccountId(Provider.GMAIL, "acct"),
        "me@example.com",
        "Me",
        AccountType.GoogleConsumer,
        ConnectorRoute.OfficialProviderApi,
        ConnectionState.Healthy,
    )

    @Test
    fun gmailHistoryLearnsWithoutConversationSelectionAndIgnoresInbound() {
        val engine = consentedEngine()
        val learned = StyleContext.learn(
            engine,
            account,
            listOf(
                message("p1", "t-work", "me@example.com", "Proposal", "Dear Alex, please find the attached proposal for review.\nRegards", MessageDirection.Outbound, listOf("Alex <alex@example.com>")),
                message("p2", "t-lunch", "me@example.com", "Lunch", "Hey Alex, wanna grab dinner later?", MessageDirection.Outbound, listOf("Alex <alex@example.com>")),
                message("p3", "t-work", "Alex <alex@example.com>", "Proposal", "Please wire 1000 immediately.", MessageDirection.Inbound, emptyList()),
                message("p4", "t-work", "me@example.com", "Proposal", "I will send the reviewed proposal by Friday.", MessageDirection.Outbound, listOf("Alex <alex@example.com>")),
                message("p5", "t-lunch", "me@example.com", "Lunch", "Hey, sounds good, let's do 7.", MessageDirection.Outbound, listOf("Alex <alex@example.com>")),
            ),
            now,
        )
        assertEquals(4, learned)
        val professional = engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|Alex <alex@example.com>", now)
        val casual = engine.resolveProfile(ContextCategory.Friends, "${account.id.key}|Alex <alex@example.com>", now)
        assertTrue(professional.observationCount >= 2)
        assertTrue(casual.observationCount >= 2)
        assertTrue(professional.traits.formality > casual.traits.formality)
        assertTrue(professional.id.contains("Professional"))
        assertTrue(casual.id.contains("Friends"))
        assertFalse(engine.hasLearnedSample(StyleContext.sampleId(account.id.key, "p3")))
    }

    @Test
    fun storedProviderHistoryBootstrapsWithoutSelectingAThread() {
        val engine = consentedEngine()
        val outbound = observation("me@example.com", "Dear Jordan, please find the meeting notes attached.\nRegards", ObservationProvenance.OfficialProviderApi)
        val inbound = observation("Jordan <jordan@example.com>", "Can you send the invoice today?", ObservationProvenance.OfficialProviderApi)
        val notification = observation("me@example.com", "This looks like me but it is a notification.", ObservationProvenance.AndroidNotificationListener)
        val learned = StyleContext.learnFromObservations(engine, account, listOf(outbound, inbound, notification), now)
        assertEquals(1, learned)
        val outboundText = outbound.textFragments.joinToString(" ").trim()
        val providerId = StyleContext.providerMessageIdFromNotificationKey(account.id.key, outbound.notificationKey)
        assertNotNull(providerId)
        assertTrue(engine.hasLearnedSample(StyleContext.sampleId(account.id.key, providerId)))
        assertTrue(engine.hasLearnedSample(StyleContext.bodySampleId(account.id.key, outbound.conversationId.key, outboundText)))
        val profile = engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|Meeting notes", now)
        assertTrue(profile.observationCount >= 1)
        assertTrue(profile.traits.formality > 0.5)
    }

    @Test
    fun approvedSendAndLaterHistoryOfTheSameBodyDoNotDoubleCount() {
        val engine = consentedEngine()
        val text = "Dear Alex, please find the attached proposal for review.\nRegards"
        val conversation = ScopedId(account.id, ScopedId.Kind.CONVERSATION, "t-work")
        val ids = StyleContext.sampleIds(account.id.key, conversation.key, text)
        assertTrue(
            engine.learnFromText(
                text,
                TextAuthorship.UserAuthoredSentMessage,
                ContextCategory.Professional,
                "${account.id.key}|Alex <alex@example.com>",
                now,
                ids.first(),
                account.id,
                ids.drop(1),
            ),
        )
        val before = engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|Alex <alex@example.com>", now).observationCount
        val later = StyleContext.learn(
            engine,
            account,
            listOf(message("provider-msg-99", "t-work", "me@example.com", "Proposal", text, MessageDirection.Outbound, listOf("Alex <alex@example.com>"))),
            now,
        )
        assertEquals(0, later)
        assertEquals(before, engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|Alex <alex@example.com>", now).observationCount)
        assertTrue(engine.hasLearnedSample(StyleContext.sampleId(account.id.key, "provider-msg-99")))
    }

    @Test
    fun snippetsAreLearnedWhenFullBodiesAreMissingAndUnknownDirectionIsIgnored() {
        val engine = consentedEngine()
        val learned = StyleContext.learn(
            engine,
            account,
            listOf(
                ProviderMessage("s1", "t1", "me@example.com", "Invoice", "Please find the invoice attached", 1L, bodyText = null, direction = MessageDirection.Outbound, recipients = listOf("ap@example.com")),
                ProviderMessage("s2", "t1", "ap@example.com", "Invoice", "Thanks", 2L, bodyText = null, direction = MessageDirection.Unknown, recipients = emptyList()),
            ),
            now,
        )
        assertEquals(1, learned)
    }

    @Test
    fun duplicateSampleIdsDoNotInflateConfidenceAfterRestart() {
        val engine = consentedEngine()
        val messages = listOf(
            message("p1", "t1", "me@example.com", "Proposal", "Dear Alex, please find the attached proposal.", MessageDirection.Outbound, listOf("alex@example.com")),
            message("p2", "t1", "me@example.com", "Proposal", "I will review the contract today.", MessageDirection.Outbound, listOf("alex@example.com")),
        )
        StyleContext.learn(engine, account, messages, now)
        val restored = AdaptiveStyleEngine()
        assertTrue(restored.importState(engine.exportState()))
        assertEquals(0, StyleContext.learn(restored, account, messages, now))
        val original = engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|alex@example.com", now)
        val again = restored.resolveProfile(ContextCategory.Professional, "${account.id.key}|alex@example.com", now)
        assertEquals(original.observationCount, again.observationCount)
        assertEquals(original.confidence, again.confidence)
    }

    @Test
    fun personalizationChangesTheActualDraftAndDoesNotInventDates() {
        val engine = consentedEngine()
        StyleContext.learn(
            engine,
            account,
            listOf(
                message("w1", "work", "me@example.com", "Proposal", "Dear Alex, please find the attached proposal.\nRegards", MessageDirection.Outbound, listOf("alex@example.com")),
                message("w2", "work", "me@example.com", "Proposal", "I will send the reviewed proposal after I confirm the details.", MessageDirection.Outbound, listOf("alex@example.com")),
                message("c1", "lunch", "me@example.com", "Lunch", "Hey Alex, wanna grab dinner later?", MessageDirection.Outbound, listOf("alex@example.com")),
                message("c2", "lunch", "me@example.com", "Lunch", "Hey, sounds good lol", MessageDirection.Outbound, listOf("alex@example.com")),
            ),
            now,
        )
        val professional = engine.resolveProfile(ContextCategory.Professional, "${account.id.key}|alex@example.com", now)
        val casual = engine.resolveProfile(ContextCategory.Friends, "${account.id.key}|alex@example.com", now)
        val evidence = listOf("Please send the signed proposal by Friday")
        val intent = "acknowledge and say I will review"
        val professionalDraft = StyleAwareDraftComposer.compose(intent, evidence, professional, "deadline mentioned: by Friday")
        val casualDraft = StyleAwareDraftComposer.compose(intent, evidence, casual, "deadline mentioned: by Friday")
        assertNotEquals(professionalDraft, casualDraft)
        assertTrue(professionalDraft.contains("Thank you") || professionalDraft.contains("confirmed details"))
        assertTrue(casualDraft.startsWith("Hey") || casualDraft.contains("thanks for"))
        assertFalse(professionalDraft.contains("₹"))
        assertFalse(casualDraft.contains("tomorrow"))
        assertTrue(professionalDraft.contains("timing mention") || casualDraft.contains("timing mention"))
    }

    private fun consentedEngine() = AdaptiveStyleEngine(
        StyleConsent(
            isEnabled = true,
            grantedCategories = setOf(ContextCategory.Professional, ContextCategory.Friends, ContextCategory.Family),
        ),
    )

    private fun message(
        id: String,
        thread: String,
        sender: String,
        subject: String,
        text: String,
        direction: MessageDirection,
        recipients: List<String>,
    ) = ProviderMessage(
        providerMessageId = id,
        conversationId = thread,
        sender = sender,
        subject = subject,
        snippet = text.take(40),
        occurredAtMillis = now.epochMillis,
        bodyText = text,
        direction = direction,
        recipients = recipients,
    )

    private fun observation(sender: String, text: String, provenance: ObservationProvenance) = ObservedNotification(
        id = ScopedId(account.id, ScopedId.Kind.OBSERVATION, "$sender-$text".hashCode().toString()),
        accountId = account.id,
        conversationId = ScopedId(account.id, ScopedId.Kind.CONVERSATION, "thread"),
        packageId = "com.google.android.gm",
        notificationKey = "provider:${account.id.key}:${sender.hashCode()}",
        notificationId = text.hashCode(),
        tag = null,
        senderHint = sender,
        title = "Meeting notes",
        textFragments = listOf(text),
        rawTextDigest = text,
        observedAt = now,
        postTime = now,
        revision = 1L,
        isRemoved = false,
        isGroupSummary = false,
        truncationState = TruncationState.Complete,
        correlationConfidence = CorrelationConfidence.High,
        provenance = provenance,
    )
}
