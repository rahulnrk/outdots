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
    fun linkedinMessageNotificationUsesDirectMessageConversationCategory() {
        val normalizer = NotificationNormalizer(ids, digest)
        val observation = normalizer.normalize(
            payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk?"),
            clock.now(),
        )
        assertEquals(NotificationCategory.DirectMessage, normalizer.deriveNotificationCategory(payload("com.linkedin.android").copy(title = "New message from Alex", text = "Can we talk?")))
        assertTrue(observation.conversationId.value.endsWith("Alex"))
    }
}
