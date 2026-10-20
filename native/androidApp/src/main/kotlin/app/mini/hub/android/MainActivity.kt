package app.mini.hub.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mini.hub.android.connector.AndroidConnectedAccountStore
import app.mini.hub.android.connector.ConnectOutcome
import app.mini.hub.android.connector.GmailSyncJobService
import app.mini.hub.android.connector.InstagramForegroundReceiver
import app.mini.hub.android.connector.InstagramLoginActivity
import app.mini.hub.android.connector.InstagramReadIngestor
import app.mini.hub.android.connector.MessengerLoginActivity
import app.mini.hub.android.notification.MiniNotificationListenerService
import app.mini.hub.android.ui.AndroidNativeHandoffLauncher
import app.mini.hub.android.ui.InboxScreen
import app.mini.hub.android.ui.InboxViewModel
import app.mini.hub.android.ui.MiniTheme
import app.mini.hub.domain.Provider
import app.mini.hub.style.StyleContext

/**
 * Main Android host Activity rendering the private communication hub.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: InboxViewModel
    private lateinit var handoffLauncher: AndroidNativeHandoffLauncher
    private val connectionStatus = mutableStateOf<String?>(null)
    private val accountsVersion = mutableStateOf(0)
    private val styleLearningEnabled = mutableStateOf(false)
    private val focusEnabledMap = mutableStateOf<Map<String, Boolean>>(emptyMap())
    private val focusGenerations = mutableMapOf<String, Long>()
    private val focusSerialExecutors = mutableMapOf<String, java.util.concurrent.ExecutorService>()
    private var instagramForegroundReceiver: InstagramForegroundReceiver? = null
    private val instagramConnectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            connectionStatus.value = "Instagram sign-in cancelled."
            return@registerForActivityResult
        }
        val data = result.data
        val accountKey = data?.getStringExtra(InstagramLoginActivity.EXTRA_RESULT_ACCOUNT_KEY)
        if (accountKey.isNullOrBlank()) {
            connectionStatus.value = "Instagram sign-in did not return an account."
            return@registerForActivityResult
        }
        connectionStatus.value = "Finishing Instagram connection..."
        Thread({
            val outcome = MiniHubRuntime.connectors.completeInstagramPhoneLocal(
                accountKey = accountKey,
                username = data.getStringExtra(InstagramLoginActivity.EXTRA_RESULT_USERNAME),
            )
            val message = when (outcome) {
                is ConnectOutcome.Connected -> {
                    val ingest = MiniHubRuntime.connectors.ingestInstagram()
                    when (ingest) {
                        is InstagramReadIngestor.Result.Success ->
                            "Instagram connected. Loaded ${ingest.persistedCount} recent message(s)" +
                                if (ingest.partial) " (partial history)." else "."
                        is InstagramReadIngestor.Result.Failed ->
                            "Instagram connected, but first read failed: ${ingest.code}."
                    }
                }
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Instagram connection in progress."
            }
            runOnUiThread {
                if (!isDestroyed) {
                    connectionStatus.value = message
                    accountsVersion.value++
                    refreshInbox()
                    startInstagramForegroundReceiveIfNeeded()
                }
            }
        }, "mini-instagram-connect").start()
    }

    private fun loadFocusStates(accounts: List<app.mini.hub.connector.ConnectedAccount>) {
        if (!MiniHubRuntime.isStorageReady) return
        BOOTSTRAP_EXECUTOR.execute {
            val map = mutableMapOf<String, Boolean>()
            accounts.forEach { acc ->
                val key = acc.id.key
                // If there is an active/pending toggle generation for this account, honor its intended optimistic state
                // or ensure we don't overwrite a newer pending toggle completion with an out-of-order background read.
                val enabled = runCatching {
                    MiniHubRuntime.matterStore.isConsentGranted(acc.id) && !MiniHubRuntime.matterStore.isExcluded(acc.id)
                }.getOrDefault(false)
                map[key] = enabled
            }
            runOnUiThread {
                if (!isDestroyed) {
                    val currentMap = focusEnabledMap.value.toMutableMap()
                    map.forEach { (k, v) ->
                        // Only update if no active generation is currently inflight for k
                        if ((focusGenerations[k] ?: 0L) % 2L == 0L) {
                            currentMap[k] = v
                        }
                    }
                    focusEnabledMap.value = currentMap
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.RTPROBE) {
            // LinkedIn remains BLOCKED separately. rtprobe now prepares Messenger owner sign-in.
            startActivity(android.content.Intent(this, MessengerLoginActivity::class.java))
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Opening your inbox…", style = MaterialTheme.typography.headlineSmall)
                    Text("Your messages will appear when MINI is ready.", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        BOOTSTRAP_EXECUTOR.execute {
            MiniHubRuntime.init(applicationContext)
            runOnUiThread { if (!isDestroyed) finishRuntimeSetup() }
        }
    }

    private fun finishRuntimeSetup() {
        val storage = MiniHubRuntime.storage
        if (storage is RuntimeStorage.Failed) {
            // Blocking: no inbox, no accounts, no sending. Nothing would be durable.
            setContent {
                MaterialTheme {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Text("MINI cannot open its storage", style = MaterialTheme.typography.headlineSmall)
                        Text(storage.userMessage, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            return
        }
        styleLearningEnabled.value = MiniHubRuntime.styleEngine.getConsent().isEnabled
        // One background job syncs every connected account (Gmail and the other API routes).
        GmailSyncJobService.schedulePeriodic(applicationContext)
        val registry = MiniHubRuntime.connectors
        handleOAuthIntent(intent)

        val accountStore = AndroidConnectedAccountStore(applicationContext)
        // Retained across rotation: the same view model (and any draft in it) is reused.
        viewModel = androidx.lifecycle.ViewModelProvider(this, app.mini.hub.android.ui.InboxViewModelHolder.factory { InboxViewModel(
            observationStore = MiniHubRuntime.observationStore,
            retrievalEngine = MiniHubRuntime.retrievalEngine,
            styleEngine = MiniHubRuntime.styleEngine,
            actionController = MiniHubRuntime.actionController,
            actionStore = MiniHubRuntime.actionStore,
            clock = MiniHubRuntime.clock,
            attentionStore = MiniHubRuntime.attentionStore,
            localDraftExperiment = MiniHubRuntime.localDraftExperiment,
            selectedSources = { MiniHubRuntime.communicationSourcePreferences.selectedSources() },
            accountStore = accountStore,
            gateway = registry.gateway,
            apiReplyAllowed = registry::canReplyViaApi,
            capabilitiesFor = registry::capabilities,
            onLearnFromApprovedText = { summary, body, recipient, authorship ->
                val category = StyleContext.forMessage(summary.accountId.provider, summary.title, body)
                val recipientId = "${summary.accountId.key}|${recipient.ifBlank { summary.senderHint }}"
                val ids = StyleContext.sampleIds(summary.accountId.key, summary.conversationId.key, body)
                val primary = ids.first()
                val extra = ids.drop(1)
                if (MiniHubRuntime.styleEngine.learnFromText(body, authorship, category, recipientId, MiniHubRuntime.clock.now(), primary, account = summary.accountId, extraSampleIds = extra)) {
                    MiniHubRuntime.styleEngine.learnFromText(body, authorship, category, null, MiniHubRuntime.clock.now(), "cat:$primary", account = summary.accountId, extraSampleIds = extra.map { "cat:$it" })
                    MiniHubRuntime.styleStore.save(MiniHubRuntime.styleEngine)
                }
            },
            conversationLoader = { summary ->
                accountStore.get(summary.accountId)?.let { registry.loadConversation(it, summary.conversationId) }
            },
            extraVisiblePackages = if (BuildConfig.DEBUG) setOf(packageName) else emptySet(),
        ) })[app.mini.hub.android.ui.InboxViewModelHolder::class.java].viewModel

        handoffLauncher = AndroidNativeHandoffLauncher(this)

        loadFocusStates(accountStore.list())

        setContent {
            MiniTheme {
                InboxScreen(
                    viewModel = viewModel,
                    handoffLauncher = handoffLauncher,
                    onRequestPermission = {
                        startActivity(MiniNotificationListenerService.createSettingsIntent())
                    },
                    sourcePreferences = MiniHubRuntime.communicationSourcePreferences,
                    accountStore = accountStore,
                    routes = registry.routes(),
                    capabilities = registry::capabilities,
                    connectionStatus = connectionStatus.value,
                    onConnect = { provider -> connect(provider) },
                    onConnectTelegram = { token -> connectTelegram(token) },
                    onConnectMatrix = { provider, url, token -> connectMatrix(provider, url, token) },
                    onStartWhatsAppPairing = { MiniHubRuntime.connectors.startWhatsAppPairing() },
                    onStartWhatsAppPhonePairing = { phone -> MiniHubRuntime.connectors.startWhatsAppPhonePairing(phone) },
                    onPollWhatsAppPairing = { MiniHubRuntime.connectors.pollWhatsAppPairing() },
                    onCancelWhatsAppPairing = { MiniHubRuntime.connectors.cancelWhatsAppPairing() },
                    onWhatsAppLinked = { linked ->
                        val message = when (val outcome = MiniHubRuntime.connectors.completeWhatsAppLink(linked)) {
                            is ConnectOutcome.Connected -> {
                            WhatsAppSyncService.start(applicationContext)
                            "WhatsApp linked: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                        }
                            is ConnectOutcome.Blocked -> outcome.reason
                            is ConnectOutcome.Started -> "WhatsApp pairing started."
                        }
                        connectionStatus.value = message
                        accountsVersion.value++
                    },
                    onConnectInstagramPhoneLocal = {
                        connectionStatus.value = "Opening Instagram sign-in..."
                        instagramConnectLauncher.launch(
                            android.content.Intent(this, InstagramLoginActivity::class.java)
                                .putExtra(InstagramLoginActivity.EXTRA_SLOT_KEY, "instagram|pending")
                                .putExtra(InstagramLoginActivity.EXTRA_RETURN_RESULT, true),
                        )
                    },
                    isFocusEnabled = { accountId ->
                        focusEnabledMap.value[accountId.key] ?: false
                    },
                    onFocusChanged = { accountId, enabled ->
                        if (MiniHubRuntime.isStorageReady) {
                            val key = accountId.key
                            val gen = (focusGenerations[key] ?: 0L) + 1L
                            focusGenerations[key] = gen
                            connectionStatus.value = "Updating Focus..."

                            val executor = synchronized(focusSerialExecutors) {
                                focusSerialExecutors.getOrPut(key) {
                                    java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "mini-focus-$key").apply { isDaemon = true } }
                                }
                            }

                            executor.execute {
                                val result = runCatching {
                                    if (enabled) {
                                        MiniHubRuntime.matterStore.setConsent(accountId, true, false)
                                    } else {
                                        MiniHubRuntime.matterStore.invalidateForAccount(accountId)
                                    }
                                }
                                runOnUiThread {
                                    if (!isDestroyed) {
                                        val currentGen = focusGenerations[key] ?: 0L
                                        if (gen == currentGen) {
                                            connectionStatus.value = if (result.isSuccess) null else "Could not update Focus. Try again."
                                            loadFocusStates(accountStore.list())
                                            accountsVersion.value++
                                            refreshInbox()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDisconnectAccount = { account ->
                        connectionStatus.value = "Disconnecting…"
                        Thread({
                            val result = runCatching { MiniHubRuntime.connectors.disconnectAccount(account) }
                            if (result.isSuccess && account.id.provider == Provider.WHATSAPP) WhatsAppSyncService.stop(applicationContext)
                            if (result.isSuccess && account.id.provider == Provider.INSTAGRAM) {
                                runOnUiThread { stopInstagramForegroundReceive() }
                            }
                            runOnUiThread {
                                if (!isDestroyed) {
                                    connectionStatus.value = if (result.isSuccess) "Disconnected. Messages stay in MINI. Reconnect to resume."
                                        else "Could not disconnect right now. Try again."
                                    accountsVersion.value++
                                }
                            }
                        }, "mini-disconnect").start()
                    },
                    onSyncNow = { syncNow() },
                    styleLearningEnabled = styleLearningEnabled.value,
                    onStyleLearningChanged = { enabled ->
                        val consent = MiniHubRuntime.styleEngine.getConsent()
                        MiniHubRuntime.styleEngine.updateConsent(consent.copy(isEnabled = enabled))
                        MiniHubRuntime.styleStore.save(MiniHubRuntime.styleEngine)
                        styleLearningEnabled.value = enabled
                        if (enabled) MiniHubRuntime.bootstrapStyleFromStoredHistory()
                    },
                    accountsVersion = accountsVersion.value,
                    onSourceSelectionChanged = { refreshInbox() },
                )
            }
        }
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) activateForeground()
    }

    private fun connect(provider: Provider) {
        connectionStatus.value = when (val outcome = MiniHubRuntime.connectors.launch(provider, this)) {
            is ConnectOutcome.Started -> "Sign-in opened in your browser. Return to MINI to finish."
            is ConnectOutcome.Blocked -> outcome.reason
            is ConnectOutcome.Connected -> "Connected ${outcome.account.displayName ?: outcome.account.providerAccountId}."
        }
    }

    private fun connectTelegram(token: String) {
        val isInstagram = token.startsWith(app.mini.hub.android.ui.INSTAGRAM_TOKEN_PREFIX)
        connectionStatus.value = if (isInstagram) "Checking the token with Instagram..." else "Checking the bot token with Telegram..."
        Thread({
            val message = if (isInstagram) {
                when (val outcome = MiniHubRuntime.connectors.instagram.connect(token.removePrefix(app.mini.hub.android.ui.INSTAGRAM_TOKEN_PREFIX))) {
                    is ConnectOutcome.Connected -> {
                        runCatching { MiniHubRuntime.connectors.syncAll("instagram_connect") }
                        "Instagram connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                    }
                    is ConnectOutcome.Blocked -> outcome.reason
                    is ConnectOutcome.Started -> "Instagram connection in progress."
                }
            } else when (val outcome = MiniHubRuntime.connectors.connectTelegram(token)) {
                is ConnectOutcome.Connected -> "Telegram bot connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}. Only chats the bot is in are visible."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Telegram connection in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
                refreshInbox()
            }
        }, "mini-telegram-connect").start()
    }

    private fun connectMatrix(provider: Provider, url: String, token: String) {
        connectionStatus.value = "Connecting to Matrix homeserver..."
        Thread({
            val message = when (val outcome = MiniHubRuntime.connectors.connectMatrixBridge(provider, url, token)) {
                is ConnectOutcome.Connected -> "Matrix bridge connected: ${outcome.account.displayName ?: outcome.account.providerAccountId}."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Connection in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-matrix-connect").start()
    }

    private fun syncNow() {
        connectionStatus.value = "Syncing connected accounts..."
        Thread({
            val summary = try { MiniHubRuntime.connectors.syncAll("manual") } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) connectionStatus.value = "Sync could not finish. Your history is kept; try again." }
                return@Thread
            }
            val ig = runCatching { MiniHubRuntime.connectors.ingestInstagram() }.getOrNull()
            runOnUiThread {
                connectionStatus.value = buildString {
                    append("Sync finished: ${summary.inserted} new, ${summary.updated} updated across ${summary.accounts} account(s).")
                    when (ig) {
                        is InstagramReadIngestor.Result.Success -> {
                            append(" Instagram +${ig.persistedCount}")
                            if (ig.partial) append(" (partial)")
                            append(".")
                            startInstagramForegroundReceiveIfNeeded()
                        }
                        is InstagramReadIngestor.Result.Failed -> {
                            if (ig.code != "instagram_phone_local_not_connected") {
                                append(" Instagram: ${ig.code}.")
                            }
                            if (ig.code == "session_expired") {
                                stopInstagramForegroundReceive()
                            }
                        }
                        null -> Unit
                    }
                    if (summary.needsAttention > 0) append(" ${summary.needsAttention} account(s) need attention.")
                }
                accountsVersion.value++
                refreshInbox()
            }
        }, "mini-manual-sync").start()
    }

    private fun refreshInbox() {
        viewModel.refresh(
            isPermissionGranted = MiniNotificationListenerService.isPermissionGranted(this),
            isListenerConnected = MiniNotificationListenerService.isConnected,
        )
    }

    override fun onResume() {
        super.onResume()
        activateForeground()
    }

    private fun activateForeground() {
        if (::viewModel.isInitialized) {
            loadFocusStates(AndroidConnectedAccountStore(applicationContext).list())
            MiniNotificationListenerService.rebindIfNeeded(this)
            refreshInbox()

            // Register reactive UI listener so background ingest updates UI immediately
            MiniHubRuntime.ingestCoordinator.setUiListener { refreshInbox() }
            MiniHubRuntime.inboxRefresh = { refreshInbox() }
            MiniHubRuntime.setWhatsAppForeground(true)
            if (MiniHubRuntime.connectors.listAccounts().any {
                    it.id.provider == Provider.WHATSAPP && it.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge &&
                        it.state != app.mini.hub.connector.ConnectionState.Disconnected
                }
            ) WhatsAppSyncService.start(applicationContext)
            startInstagramForegroundReceiveIfNeeded()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (!MiniHubRuntime.isStorageReady) return
        val registry = MiniHubRuntime.connectors
        val handled = registry.handlesRedirect(uri)
        android.util.Log.i("MiniOAuth", "Intent received: scheme=${uri.scheme} path=${uri.path} handled=$handled")
        if (!handled) return
        intent.data = null
        connectionStatus.value = "Finishing connection..."
        Thread({
            val message = when (val outcome = registry.completeRedirect(uri)) {
                is ConnectOutcome.Connected -> "Connected ${outcome.account.displayName ?: outcome.account.providerAccountId} (${outcome.account.id.provider.wire})."
                is ConnectOutcome.Blocked -> outcome.reason
                is ConnectOutcome.Started -> "Sign-in is in progress."
            }
            runOnUiThread {
                connectionStatus.value = message
                accountsVersion.value++
            }
        }, "mini-oauth-callback").start()
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) viewModel.onAppBackgrounded()
        if (MiniHubRuntime.isStorageReady) {
            MiniHubRuntime.ingestCoordinator.setUiListener(null)
            MiniHubRuntime.inboxRefresh = null
            MiniHubRuntime.setWhatsAppForeground(false)
            stopInstagramForegroundReceive()
        }
    }

    private fun startInstagramForegroundReceiveIfNeeded() {
        if (!MiniHubRuntime.isStorageReady) return
        val account = MiniHubRuntime.connectors.listAccounts().firstOrNull {
            it.id.provider == Provider.INSTAGRAM &&
                it.route == app.mini.hub.connector.ConnectorRoute.PhoneLocalBridge &&
                it.state == app.mini.hub.connector.ConnectionState.Healthy
        } ?: run {
            stopInstagramForegroundReceive()
            return
        }
        val receiver = instagramForegroundReceiver ?: InstagramForegroundReceiver(
            sync = { MiniHubRuntime.connectors.ingestInstagram() },
        ) { result ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is InstagramReadIngestor.Result.Success -> {
                        refreshInbox()
                    }
                    is InstagramReadIngestor.Result.Failed -> {
                        connectionStatus.value = "Instagram receive: ${result.code}"
                        if (result.code == "session_expired") {
                            stopInstagramForegroundReceive()
                        }
                    }
                }
            }
        }.also { instagramForegroundReceiver = it }
        receiver.start(account.id)
    }

    private fun stopInstagramForegroundReceive() {
        instagramForegroundReceiver?.stop()
    }
    companion object {
        private val BOOTSTRAP_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "mini-runtime-bootstrap") }
    }

}
