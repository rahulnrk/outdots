package app.mini.hub.android

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import app.mini.hub.android.intelligence.LocalAiTrace
import app.mini.hub.android.intelligence.LocalDraftExperiment
import app.mini.hub.android.intelligence.onAndroidMainThread
import app.mini.hub.action.ActionController
import app.mini.hub.action.DigestProvider
import app.mini.hub.action.DomainActionIdGenerator
import app.mini.hub.domain.Clock
import app.mini.hub.domain.IdGenerator
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.BoundedIngestQueue
import app.mini.hub.observation.CommunicationSourcePolicy
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.DeliveryAccountingLedger
import app.mini.hub.observation.NotificationDeduplicator
import app.mini.hub.observation.NotificationNormalizer
import app.mini.hub.observation.NotificationRawPayload
import app.mini.hub.observation.ObservationIngestCoordinator
import app.mini.hub.persistence.ActionStore
import app.mini.hub.persistence.ObservationStore
import app.mini.hub.persistence.SqlCipherActionStore
import app.mini.hub.persistence.SqlCipherObservationStore
import app.mini.hub.retrieval.ScopedRetrievalEngine
import app.mini.hub.style.AdaptiveStyleEngine
import app.mini.hub.style.ContextCategory
import app.mini.hub.style.StyleConsent
import app.mini.hub.style.StyleContext
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Process-wide singleton runtime ensuring headless notification ingestion,
 * durable encrypted persistence, and thread-safe dispatch across components.
 */
object MiniHubRuntime {
    const val MAX_BOOTSTRAP_CONVERSATIONS = 50
    const val MAX_BOOTSTRAP_SAMPLES_PER_ACCOUNT = 200
    private val lock = Any()
    @Volatile private var isInitialized = false

    lateinit var clock: Clock
        private set
    lateinit var observationStore: ObservationStore
        private set
    lateinit var actionStore: ActionStore
        private set
    lateinit var attentionStore: app.mini.hub.attention.AttentionStore
        private set
    lateinit var ingestCoordinator: ObservationIngestCoordinator
        private set
    val ledger = DeliveryAccountingLedger(2000)
    lateinit var retrievalEngine: ScopedRetrievalEngine
        private set
    lateinit var styleEngine: AdaptiveStyleEngine
        private set
    lateinit var styleStore: app.mini.hub.android.intelligence.EncryptedStyleProfileStore
        private set
    lateinit var actionController: ActionController
        private set
    lateinit var localDraftExperiment: LocalDraftExperiment
        private set
    lateinit var communicationSourcePreferences: CommunicationSourcePreferences
        private set
    lateinit var httpTransport: app.mini.hub.connector.ProviderHttpTransport
        private set
    /** Every API route: connect, refresh, sync, conversation reads, gated sending. */
    lateinit var connectors: app.mini.hub.android.connector.AndroidConnectorRegistry
        private set
    val connectorGateway: app.mini.hub.action.ProviderGateway get() = connectors.gateway
    val gmailOAuth: app.mini.hub.android.connector.GmailOAuthClient get() = connectors.gmailOAuth
    val outlookOAuth: app.mini.hub.android.connector.OutlookOAuthClient get() = connectors.outlookOAuth

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    /** Null until [init]; [RuntimeStorage.Failed] means stores and connectors were not wired. */
    @Volatile var storage: RuntimeStorage? = null
        private set
    val isStorageReady: Boolean get() = storage is RuntimeStorage.Ready

    /** Count-free state line for hosts that skip work because storage is unavailable. */
    fun logState(line: String) { android.util.Log.w("MiniRuntime", line) }

    fun isCommunicationSourcePackageSelected(packageId: String): Boolean =
        isStorageReady && ::communicationSourcePreferences.isInitialized &&
            CommunicationSource.forExactPackage(packageId) in communicationSourcePreferences.selectedSources()

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return

            var counter = 1L
            val idGen = IdGenerator { prefix -> "$prefix-${System.currentTimeMillis()}-${counter++}" }
            clock = Clock { UtcInstant(System.currentTimeMillis()) }
            communicationSourcePreferences = CommunicationSourcePreferences(context.applicationContext)
            val communicationSourcePolicy = CommunicationSourcePolicy {
                communicationSourcePreferences.selectedSources()
            }
            val digest = DigestProvider { payload ->
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }

            // No memory-only fallback: data would silently vanish on restart.
            val opened = RuntimeStorage.open(
                openObservations = { SqlCipherObservationStore(context.applicationContext) },
                openActions = { SqlCipherActionStore(context.applicationContext) },
                openAttention = { app.mini.hub.persistence.SqlCipherAttentionStore(context.applicationContext) },
            )
            storage = opened
            if (opened is RuntimeStorage.Failed) {
                logState("storage=unavailable cause=${opened.cause}")
                // Stay inert: no stores, connectors or background work. Hosts check isStorageReady.
                isInitialized = true
                return
            }
            opened as RuntimeStorage.Ready
            observationStore = opened.observations
            actionStore = opened.actions
            attentionStore = opened.attention

            val normalizer = NotificationNormalizer(idGen, digest)
            val deduplicator = NotificationDeduplicator()
            val queue = BoundedIngestQueue<NotificationRawPayload>(256)

            ingestCoordinator = ObservationIngestCoordinator(
                queue = queue,
                normalizer = normalizer,
                deduplicator = deduplicator,
                store = observationStore,
                clock = clock,
                ledger = ledger,
                isPayloadEligible = communicationSourcePolicy::isEligible,
            )

            retrievalEngine = ScopedRetrievalEngine(observationStore)
            styleEngine = AdaptiveStyleEngine(
                StyleConsent(
                    isEnabled = false,
                    grantedCategories = setOf(ContextCategory.Professional, ContextCategory.Friends, ContextCategory.Family),
                ),
            )
            styleStore = app.mini.hub.android.intelligence.EncryptedStyleProfileStore(context.applicationContext)
            styleStore.restoreInto(styleEngine)
            actionController = ActionController(
                clock = clock,
                digest = digest,
                ids = DomainActionIdGenerator(idGen),
            )
            // Before any UI can dispatch: an interrupted send becomes OutcomeUnknown, never re-sent.
            StartupRecovery.recoverOutbox(actionStore, actionController, ::logState)
            localDraftExperiment = LocalDraftExperiment(context.applicationContext)
            httpTransport = app.mini.hub.android.connector.AndroidProviderHttpTransport()
            // Gates are cleared only by real configuration and owner-recorded evidence (GatePolicy),
            // never by defaults, so an unconfigured or unqualified route stays visibly blocked.
            connectors = app.mini.hub.android.connector.AndroidConnectorRegistry(
                context.applicationContext, observationStore, digest, httpTransport,
                onMessagesFetched = { account, messages ->
                    val learned = StyleContext.learn(styleEngine, account, messages, clock.now())
                    if (learned > 0) styleStore.save(styleEngine)
                },
            )

            val app = context.applicationContext as Application
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        timedCallback("onTrimMemory level=$level") { localDraftExperiment.onMemoryPressure() }
                    }
                }
                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {
                    timedCallback("onLowMemory") { localDraftExperiment.onMemoryPressure() }
                }
            })

            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var foregroundActivities = 0

                override fun onActivityStarted(activity: Activity) {
                    foregroundActivities++
                    timedCallback("onActivityStarted") { localDraftExperiment.setForeground(true) }
                }

                override fun onActivityStopped(activity: Activity) {
                    foregroundActivities--
                    if (foregroundActivities <= 0) {
                        timedCallback("onActivityStopped") { localDraftExperiment.setForeground(false) }
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })

            isInitialized = true
            backgroundExecutor.execute { bootstrapStyleFromStoredHistory() }
        }
    }

    /**
     * Runs a lifecycle callback's local-AI work and logs how long it held the calling thread.
     * Measured with SystemClock.elapsedRealtimeNanos on the real callback thread.
     */
    private inline fun timedCallback(name: String, block: () -> Unit) {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1_000
        LocalAiTrace.log("MAIN_CALLBACK name=$name mainThread=${onAndroidMainThread()} durationUs=$micros")
    }

    /**
     * Learns compact style from already stored provider history without requiring a conversation
     * to be opened. Notification-only rows are ignored.
     */
    fun bootstrapStyleFromStoredHistory() {
        if (!isInitialized || !isStorageReady || !styleEngine.getConsent().isEnabled) return
        var learned = 0
        val now = clock.now()
        connectors.listAccounts().forEach { account ->
            val observations = observationStore.getConversationSummaries(account.id)
                .take(MAX_BOOTSTRAP_CONVERSATIONS)
                .flatMap { observationStore.getObservationsForConversation(it.conversationId) }
            learned += StyleContext.learnFromObservations(
                styleEngine,
                account,
                observations,
                now,
                maxSamples = MAX_BOOTSTRAP_SAMPLES_PER_ACCOUNT,
            )
        }
        if (learned > 0) styleStore.save(styleEngine)
    }

    /**
     * Enqueues a notification and schedules immediate non-blocking background persistence.
     */
    fun onNotificationReceived(payload: NotificationRawPayload) {
        if (!isInitialized) return
        if (!RuntimeStorage.runIfReady(storage, "notification_ingest", ::logState) {}) return
        ingestCoordinator.enqueue(payload)
        backgroundExecutor.execute {
            ingestCoordinator.drainAndProcessAll(100)
        }
    }
}
