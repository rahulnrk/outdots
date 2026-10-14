package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant
import app.mini.hub.persistence.InMemoryObservationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunicationSourcesTest {
    private val clock = Clock { UtcInstant(1_700_000_000_000L) }
    private val digest = DigestProvider { it }
    private val ids = IdGenerator { prefix -> "$prefix-1" }

    private fun payload(packageId: String, tag: String? = null) = NotificationRawPayload(
        key = "$packageId-key",
        packageId = packageId,
        notificationId = 1,
        tag = tag,
        postTimeMillis = clock.now().epochMillis,
        senderHint = "Sender",
        text = "Message",
    )

    @Test
    fun unrelatedDeliveryShoppingAndSystemPackagesAreExcludedBeforeNormalization() {
        val policy = CommunicationSourcePolicy { setOf(CommunicationSource.Gmail, CommunicationSource.WhatsApp) }
        val coordinator = ObservationIngestCoordinator(
            normalizer = NotificationNormalizer(ids, digest),
            store = InMemoryObservationStore(),
            clock = clock,
            isPayloadEligible = policy::isEligible,
        )

        assertEquals(IngestOfferResult.Accepted, coordinator.enqueue(payload("com.google.android.gm")))
        assertEquals(IngestOfferResult.Accepted, coordinator.enqueue(payload("com.whatsapp")))
        assertEquals(IngestOfferResult.Filtered, coordinator.enqueue(payload("com.amazon.mShop.android.shopping")))
        assertEquals(IngestOfferResult.Filtered, coordinator.enqueue(payload("com.android.systemui")))
        assertEquals(IngestOfferResult.Filtered, coordinator.enqueue(payload("com.google.android.apps.maps")))
        assertEquals(2, coordinator.queue.size)
    }

    @Test
    fun exactPackageMatchingDoesNotAuthorizeLookalikePackages() {
        val policy = CommunicationSourcePolicy { setOf(CommunicationSource.Gmail) }

        assertTrue(policy.isEligiblePackage("com.google.android.gm"))
        assertFalse(policy.isEligiblePackage("com.google.android.gm.fake"))
        assertFalse(policy.isEligiblePackage("com.example.whatsapp"))
    }

    @Test
    fun syntheticHarnessCanRemainAvailableWithoutCreatingACommunicationAccount() {
        val policy = CommunicationSourcePolicy { emptySet() }
        assertTrue(policy.isEligible(payload("app.mini.hub", tag = "synthetic_test")))
        assertEquals(null, policy.sourceForPackage("app.mini.hub"))
    }

    @Test
    fun linkedinKeepsMessagesAndExcludesNonCommunicationAlerts() {
        val policy = CommunicationSourcePolicy { setOf(CommunicationSource.LinkedIn) }
        assertEquals(
            IngestOfferResult.Accepted,
            policy.let { if (it.isEligible(payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk?"))) IngestOfferResult.Accepted else IngestOfferResult.Filtered },
        )
        assertEquals(
            IngestOfferResult.Filtered,
            policy.let { if (it.isEligible(payload("com.linkedin.android").copy(title = "New connection request", text = "Connect with Alex"))) IngestOfferResult.Accepted else IngestOfferResult.Filtered },
        )
    }

    @Test
    fun linkedinActivityIsOptInAndClassifiedSeparatelyFromMessages() {
        val connection = payload("com.linkedin.android").copy(title = "New connection request", text = "Connect with Alex")
        val disabled = CommunicationSourcePolicy(
            selectedSources = { setOf(CommunicationSource.LinkedIn) },
            selectedLinkedInActivities = { emptySet() },
        )
        val enabled = CommunicationSourcePolicy(
            selectedSources = { setOf(CommunicationSource.LinkedIn) },
            selectedLinkedInActivities = { setOf(LinkedInActivityType.ConnectionRequests) },
        )
        assertEquals(LinkedInActivityType.ConnectionRequests, classifyLinkedInActivity(connection))
        assertFalse(disabled.isEligible(connection))
        assertTrue(enabled.isEligible(connection))
    }

    @Test
    fun linkedinMessageNotificationUsesDirectMessageConversationCategory() {
        val normalizer = NotificationNormalizer(ids, digest)
        val observation = normalizer.normalize(
            payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk?"),
            clock.now(),
        )
        assertEquals(NotificationCategory.DirectMessage, normalizer.deriveNotificationCategory(payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk?")))
        assertTrue(observation.conversationId.value.endsWith("Alex"))
    }

    @Test
    fun mixedBatchKeepsMessagesAndDropsNonCommunication() {
        val policy = CommunicationSourcePolicy(
            selectedSources = {
                setOf(
                    CommunicationSource.WhatsApp,
                    CommunicationSource.Gmail,
                    CommunicationSource.Instagram,
                    CommunicationSource.LinkedIn,
                )
            },
            selectedLinkedInActivities = { emptySet() },
        )
        assertTrue(policy.isEligible(payload("com.whatsapp").copy(title = "Alice", text = "On my way", category = "msg")))
        assertTrue(policy.isEligible(payload("com.google.android.gm").copy(title = "Pat", text = "Invoice attached", category = "email")))
        assertFalse(policy.isEligible(payload("com.google.android.gm").copy(title = "Gmail", text = "Deals for you", category = "promo")))
        assertFalse(policy.isEligible(payload("com.whatsapp").copy(title = "WhatsApp", text = "Backup in progress", isOngoing = true)))
        assertFalse(policy.isEligible(payload("com.whatsapp").copy(title = "Family", text = "5 new messages", isGroupSummary = true, category = "msg")))
        assertFalse(policy.isEligible(payload("com.instagram.android").copy(title = "Sam", text = "liked your photo", category = "social")))
        assertTrue(policy.isEligible(payload("com.instagram.android").copy(title = "Sam", text = "Are you free?", hasMessagingStyle = true, category = "msg")))
        assertTrue(policy.isEligible(payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk about the job?")))
        assertFalse(policy.isEligible(payload("com.linkedin.android").copy(title = "Job alert", text = "12 new roles for you")))
        assertEquals(
            CommunicationKind.ConversationMessage,
            classifyCommunication(payload("com.linkedin.android").copy(title = "New message from Alex", text = "Did you see my post?")),
        )
    }

    @Test
    fun linkedinActivityStaysOutOfConversationSummaries() {
        val job = ConversationObservationSummary(
            conversationId = app.mini.hub.domain.ScopedId(app.mini.hub.domain.AccountId(app.mini.hub.domain.Provider.LINKEDIN, "local_user"), app.mini.hub.domain.ScopedId.Kind.CONVERSATION, "li_activity_1"),
            accountId = app.mini.hub.domain.AccountId(app.mini.hub.domain.Provider.LINKEDIN, "local_user"),
            title = "Job alert",
            appName = "LinkedIn",
            senderHint = "LinkedIn",
            packageId = "com.linkedin.android",
            latestSnippet = "12 new roles for you",
            category = NotificationCategory.SocialUpdate,
            coverage = ConversationCoverage.NotificationOnly,
            coverageExplanation = "notification",
            latestEventAt = clock.now(),
            observationCount = 1,
            unreadCount = 1,
            isRemoved = false,
        )
        val dm = job.copy(
            conversationId = app.mini.hub.domain.ScopedId(job.accountId, app.mini.hub.domain.ScopedId.Kind.CONVERSATION, "Alex"),
            title = "New message from Alex",
            latestSnippet = "Can we talk?",
            category = NotificationCategory.DirectMessage,
        )
        assertTrue(isLinkedInActivitySummary(job))
        assertFalse(isConversationSummary(job))
        assertTrue(isConversationSummary(dm))
        assertEquals(
            linkedInActivityConversationValue("Job alert", "12 new roles"),
            linkedInActivityConversationValue("Job alert", "12 new roles"),
        )
    }

    @Test
    fun acceptedDirectMessagesSurvivePersistAndSummaryRebuild() {
        val store = InMemoryObservationStore()
        val policy = CommunicationSourcePolicy(
            selectedSources = {
                setOf(CommunicationSource.Instagram, CommunicationSource.LinkedIn, CommunicationSource.X)
            },
        )
        val coordinator = ObservationIngestCoordinator(
            normalizer = NotificationNormalizer(ids, digest),
            store = store,
            clock = clock,
            isPayloadEligible = policy::isEligible,
        )
        assertEquals(
            IngestOfferResult.Accepted,
            coordinator.enqueue(
                payload("com.instagram.android").copy(
                    title = "Sam",
                    text = "Are you free to look at my post?",
                    hasMessagingStyle = true,
                    category = "msg",
                ),
            ),
        )
        assertEquals(
            IngestOfferResult.Accepted,
            coordinator.enqueue(
                payload("com.linkedin.android").copy(
                    title = "New message from Alex",
                    text = "Can we talk about the job?",
                ),
            ),
        )
        assertEquals(
            IngestOfferResult.Filtered,
            coordinator.enqueue(
                payload("com.instagram.android").copy(title = "Sam", text = "liked your photo", category = "social"),
            ),
        )
        assertEquals(
            IngestOfferResult.Filtered,
            coordinator.enqueue(
                payload("com.linkedin.android").copy(title = "Job alert", text = "12 new roles for you"),
            ),
        )
        assertTrue(coordinator.processNext() is IngestProcessResult.Created)
        assertTrue(coordinator.processNext() is IngestProcessResult.Created)
        val summaries = store.getConversationSummaries()
        assertEquals(2, summaries.size)
        assertTrue(summaries.all(::isConversationSummary))
        assertTrue(summaries.none(::isLinkedInActivitySummary))
        assertTrue(summaries.any { it.packageId == "com.instagram.android" && it.category == NotificationCategory.DirectMessage })
        assertTrue(summaries.any { it.packageId == "com.linkedin.android" && it.category == NotificationCategory.DirectMessage })
        val rebuilt = store.getConversationSummaries()
        assertTrue(rebuilt.all(::isConversationSummary))
        rebuilt.filter { it.packageId == "com.instagram.android" }.forEach { summary ->
            val recovered = summary.copy(category = NotificationCategory.SocialUpdate)
            assertTrue(isConversationSummary(recovered), "legacy Instagram DMs must remain conversations after restart")
        }
    }
}
