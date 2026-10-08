package app.mini.hub.android

import android.content.Context
import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.BoundedIngestQueue
import app.mini.hub.observation.NotificationDeduplicator
import app.mini.hub.observation.NotificationNormalizer
import app.mini.hub.observation.NotificationRawPayload
import app.mini.hub.observation.ObservationIngestCoordinator
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.InMemoryActionStore
import app.mini.hub.persistence.InMemoryObservationStore
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.persistence.SqlCipherActionStore
import app.mini.hub.persistence.SqlCipherObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Process-wide singleton runtime ensuring headless notification ingestion,
 * durable encrypted persistence, and thread-safe dispatch across components.
 */
object MiniHubRuntime {
    private val lock = Any()
    @Volatile private var isInitialized = false

    lateinit var clock: Clock
        private set
    lateinit var observationStore: ObservationStore
        private set
    lateinit var actionStore: ActionStore
        private set
    lateinit var ingestCoordinator: ObservationIngestCoordinator
        private set
    lateinit var retrievalEngine: ScopedRetrievalEngine
        private set
    lateinit var styleEngine: AdaptiveStyleEngine
        private set
    lateinit var actionController: ActionController
        private set

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return

            var counter = 1L
            val idGen = IdGenerator { prefix -> "$prefix-${System.currentTimeMillis()}-${counter++}" }
            clock = Clock { UtcInstant(System.currentTimeMillis()) }
            val digest = DigestProvider { payload ->
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }

            observationStore = try {
                SqlCipherObservationStore(context.applicationContext)
            } catch (_: Throwable) {
                InMemoryObservationStore()
            }

            actionStore = try {
                SqlCipherActionStore(context.applicationContext)
            } catch (_: Throwable) {
                InMemoryActionStore()
            }

            val normalizer = NotificationNormalizer(idGen, digest)
            val deduplicator = NotificationDeduplicator()
            val queue = BoundedIngestQueue<NotificationRawPayload>(256)

            ingestCoordinator = ObservationIngestCoordinator(
                queue = queue,
                normalizer = normalizer,
                deduplicator = deduplicator,
                store = observationStore,
                clock = clock,
            )

            retrievalEngine = ScopedRetrievalEngine(observationStore)
            styleEngine = AdaptiveStyleEngine()
            actionController = ActionController(
                clock = clock,
                digest = digest,
                ids = DomainActionIdGenerator(idGen),
            )

            isInitialized = true
        }
    }

    /**
     * Enqueues a notification and schedules immediate non-blocking background persistence.
     */
    fun onNotificationReceived(payload: NotificationRawPayload) {
        if (!isInitialized) return
        ingestCoordinator.enqueue(payload)
        backgroundExecutor.execute {
            ingestCoordinator.drainAndProcessAll(100)
        }
    }
}
