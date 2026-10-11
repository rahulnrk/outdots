package app.mini.hub.action

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for versioned, unambiguous canonical encoding of ExactActionPayload.
 * Prevents delimiter collision attacks like subject="x|body:y" body="z" colliding with subject="x" body="y|body:z".
 */
class CanonicalActionEncodingTest {

    private fun payload(subject: String?, body: String, replyTarget: String? = null, sources: List<SourceVersion> = emptyList()) = ExactActionPayload(
        accountId = "gmail|user_1",
        conversationId = "gmail|user_1|conversation|thread_1",
        route = ActionRoute.Api,
        recipients = listOf("alice@example.com"),
        subject = subject,
        body = body,
        attachments = emptyList(),
        sourceVersions = sources,
        grantVersion = "grant-v1-api-send",
        policyVersion = "policy-v1-api-review",
        replyTarget = replyTarget,
    )

    @Test(timeout = 30_000)
    fun delimiterCollisionProducesDifferentCanonicalOutput() {
        // Attack payload 1: subject has embedded '|body:' delimiter
        val p1 = payload(subject = "x|body:y", body = "z")
        // Attack payload 2: standard subject and body that in unescaped format would produce identical string
        val p2 = payload(subject = "x", body = "y|body:z")

        assertNotEquals(p1.canonical(), p2.canonical(), "Delimiter collision must produce distinct canonical strings")
    }

    @Test(timeout = 30_000)
    fun canonicalEncodingIncludesSourceVersionsDeterministically() {
        val s1 = listOf(SourceVersion("obs_1", 1L), SourceVersion("obs_2", 2L))
        val s2 = listOf(SourceVersion("obs_2", 2L), SourceVersion("obs_1", 1L))

        val p1 = payload("Hello", "World", sources = s1)
        val p2 = payload("Hello", "World", sources = s2)

        assertEquals(p1.canonical(), p2.canonical(), "Source version ordering in canonical must be sorted deterministically")
    }

    @Test(timeout = 30_000)
    fun changingSourceRevisionChangesDigest() {
        val p1 = payload("Hello", "World", sources = listOf(SourceVersion("obs_1", 1L)))
        val p2 = payload("Hello", "World", sources = listOf(SourceVersion("obs_1", 2L)))

        assertNotEquals(p1.canonical(), p2.canonical(), "A changed source revision must invalidate canonical digest")
    }
}
