package app.mini.hub.observation

import app.mini.hub.action.DigestProvider
import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
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

    @Test
    fun messagingStyleBeatsIncidentalLikePhraseAndGenuineActivityStaysOut() {
        val message = payload("com.instagram.android").copy(
            title = "Sam",
            text = "I liked your post, can you send the file?",
            hasMessagingStyle = true,
            category = "msg",
        )
        val activity = payload("com.instagram.android").copy(
            title = "Sam",
            text = "I liked your post",
            category = "social",
        )
        assertEquals(CommunicationKind.ConversationMessage, classifyCommunication(message))
        assertEquals(CommunicationKind.NotCommunication, classifyCommunication(activity))
        val messageSummary = ConversationObservationSummary(
            conversationId = app.mini.hub.domain.ScopedId(app.mini.hub.domain.AccountId(app.mini.hub.domain.Provider.INSTAGRAM, "local"), app.mini.hub.domain.ScopedId.Kind.CONVERSATION, "Sam"),
            accountId = app.mini.hub.domain.AccountId(app.mini.hub.domain.Provider.INSTAGRAM, "local"),
            title = "Sam",
            appName = "Instagram",
            senderHint = "Sam",
            packageId = "com.instagram.android",
            latestSnippet = "I liked your post, can you send the file?",
            category = NotificationCategory.DirectMessage,
            coverage = ConversationCoverage.NotificationOnly,
            coverageExplanation = "notification",
            latestEventAt = clock.now(),
            observationCount = 1,
            unreadCount = 1,
            isRemoved = false,
        )
        val activitySummary = messageSummary.copy(
            latestSnippet = "I liked your post",
            category = NotificationCategory.SocialUpdate,
        )
        assertTrue(isConversationSummary(messageSummary))
        assertFalse(isConversationSummary(activitySummary))
    }

    @Test
    fun regression_legacyNullCategoryFallbackAndGeneralNotificationExcluded() {
        val legacyObs = ObservedNotification(
            id = ScopedId(AccountId(Provider.WHATSAPP, "local"), ScopedId.Kind.OBSERVATION, "obs1"),
            accountId = AccountId(Provider.WHATSAPP, "local"),
            conversationId = ScopedId(AccountId(Provider.WHATSAPP, "local"), ScopedId.Kind.CONVERSATION, "alert_conv"),
            packageId = "com.example.alert",
            notificationKey = "key1",
            notificationId = 1,
            tag = null,
            senderHint = "System",
            title = "Alert",
            textFragments = listOf("Service status update"),
            rawTextDigest = "digest",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = null, // legacy null category
        )
        val derivedCat = categoryForStoredObservation(legacyObs)
        assertEquals(NotificationCategory.GeneralNotification, derivedCat)

        val summary = ConversationObservationSummary(
            conversationId = legacyObs.conversationId,
            accountId = legacyObs.accountId,
            title = "Alert",
            appName = "Alert",
            senderHint = "System",
            packageId = legacyObs.packageId,
            latestSnippet = "Service status update",
            category = derivedCat,
            coverage = ConversationCoverage.NotificationOnly,
            coverageExplanation = "notification",
            latestEventAt = clock.now(),
            observationCount = 1,
            unreadCount = 1,
            isRemoved = false,
        )
        assertFalse(isConversationSummary(summary))
    }

    @Test
    fun regression_promotionalEmailsAndGenuineMessagesPreserved() {
        val promoEmail = ObservedNotification(
            id = ScopedId(AccountId(Provider.GMAIL, "local"), ScopedId.Kind.OBSERVATION, "obs2"),
            accountId = AccountId(Provider.GMAIL, "local"),
            conversationId = ScopedId(AccountId(Provider.GMAIL, "local"), ScopedId.Kind.CONVERSATION, "promo_conv"),
            packageId = "com.google.android.gm",
            notificationKey = "key2",
            notificationId = 2,
            tag = null,
            senderHint = "Store <store@example.com>",
            title = "Special Offer",
            textFragments = listOf("Check out our job openings and post offers this week!"),
            rawTextDigest = "digest2",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.OfficialProviderApi,
            notificationCategory = NotificationCategory.DirectMessage,
        )
        val summary = ConversationObservationSummary(
            conversationId = promoEmail.conversationId,
            accountId = promoEmail.accountId,
            title = "Special Offer",
            appName = "Gmail",
            senderHint = promoEmail.senderHint,
            packageId = promoEmail.packageId,
            latestSnippet = promoEmail.textFragments.first(),
            category = promoEmail.notificationCategory!!,
            coverage = ConversationCoverage.PartialApiSync,
            coverageExplanation = "email",
            latestEventAt = clock.now(),
            observationCount = 1,
            unreadCount = 1,
            isRemoved = false,
        )
        assertTrue(isConversationSummary(summary))
        assertEquals(CommunicationKind.ConversationMessage, classifyCommunication(NotificationRawPayload(
            key = "key2",
            packageId = promoEmail.packageId,
            notificationId = 2,
            postTimeMillis = clock.now().epochMillis,
            senderHint = promoEmail.senderHint,
            title = promoEmail.title,
            text = promoEmail.textFragments.first(),
        )))
    }

    @Test
    fun wp01_socialRecommendationFilteredOutAndGenuineMessagesMentioningJobPostLikePreserved() {
        val recommendation = payload("com.instagram.android").copy(
            title = "Instagram",
            text = "People you may know: John Doe is suggested for you.",
        )
        assertEquals(CommunicationKind.NotCommunication, classifyCommunication(recommendation))

        val genuineMessage = payload("com.whatsapp").copy(
            title = "John",
            text = "Hey, did you see that job post I liked earlier? Let's discuss.",
            hasMessagingStyle = true,
            category = "msg",
        )
        assertEquals(CommunicationKind.ConversationMessage, classifyCommunication(genuineMessage))
    }

    @Test
    fun wp01_facebookKatanaRecommendationFilteredOut() {
        val fbRecommendation = payload("com.facebook.katana").copy(
            title = "Example Person",
            text = "You might like Example Person.",
        )
        assertEquals(CommunicationKind.NotCommunication, classifyCommunication(fbRecommendation))

        val fbMessage = payload("com.facebook.katana").copy(
            title = "Example Person",
            text = "Hey, can you send me that document?",
            hasMessagingStyle = true,
            category = "msg",
        )
        assertEquals(CommunicationKind.ConversationMessage, classifyCommunication(fbMessage))
    }

    @Test
    fun wp01_storedMisclassifiedDirectMessageReconciledAndExcluded() {
        val misclassifiedObs = ObservedNotification(
            id = ScopedId(AccountId(Provider.FACEBOOK, "local"), ScopedId.Kind.OBSERVATION, "obs_mis"),
            accountId = AccountId(Provider.FACEBOOK, "local"),
            conversationId = ScopedId(AccountId(Provider.FACEBOOK, "local"), ScopedId.Kind.CONVERSATION, "rec_conv"),
            packageId = "com.facebook.katana",
            notificationKey = "rec_key",
            notificationId = 99,
            tag = null,
            senderHint = "Example Person",
            title = "Example Person",
            textFragments = listOf("You might like Example Person."),
            rawTextDigest = "digest_mis",
            observedAt = clock.now(),
            postTime = clock.now(),
            revision = 1L,
            isRemoved = false,
            isGroupSummary = false,
            isOngoing = false,
            truncationState = TruncationState.Complete,
            correlationConfidence = CorrelationConfidence.High,
            provenance = ObservationProvenance.AndroidNotificationListener,
            notificationCategory = null, // unclassified legacy row falling back to inference
        )
        val reconciledCat = categoryForStoredObservation(misclassifiedObs)
        assertEquals(NotificationCategory.GeneralNotification, reconciledCat)
    }
}
