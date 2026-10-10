package app.mini.hub.connector

import app.mini.hub.domain.Provider

/**
 * What MINI can do through one route. Everything defaults to disabled: a capability is only
 * usable when the adapter implements it, the account actually granted the required scopes, and
 * every release gate for it is cleared (see [CapabilityResolver]).
 */
enum class Capability {
    ListHistory, ReadBody, IncrementalSync, Search, ReadAttachments,
    SendNew, Reply, EditOwn, DeleteOwn, MarkRead, React, Archive,
    /** Feeding message text to MINI's local AI (summaries, drafts, embeddings). */
    LocalAiDrafting,
}

/** Account kinds are explicit so a business/bot route is never shown as a personal inbox. */
enum class RouteAccountKind { PersonalInbox, WorkWorkspace, BusinessAccount, Bot, NotificationObserver }

/** Delivery status of the adapter in this codebase, separate from live-account evidence. */
enum class ImplementationStatus {
    /** Adapter code + fixture contract tests exist. Live qualification still recorded separately. */
    AdapterImplemented,
    /** Partial coverage through Android notification observation and native handoff only. */
    NotificationFallbackOnly,
    /** Official route exists but needs infrastructure/partner/business setup MINI has not chosen yet. */
    DeferredExternalRequirement,
    /** No supported official route established; MINI will not use private endpoints. */
    UnsupportedNoOfficialRoute,
}

/**
 * A reason a capability stays off even when code and scopes exist. Gates are cleared by recorded
 * evidence (a test run, an approval, a legal decision), never by code defaults.
 */
enum class ReleaseGate(val description: String) {
    LiveAccountQualification("Two real accounts connected, synced, revoked and reconnected on a device with recorded evidence."),
    SendQualification("Exact-review send, ambiguous-outcome reconciliation and recipient correctness verified live."),
    GoogleRestrictedScopeVerification("Google OAuth verification (restricted Gmail scopes; may require a security assessment) before external users."),
    MicrosoftAppRegistration("Entra app registration with Android redirect/signature and delegated permissions."),
    TenantAdminConsent("Work/school tenant administrator consent for the delegated permissions."),
    SlackAppInstallation("Slack app with PKCE public-client setting, user scopes, and workspace installation approval."),
    GoogleChatAppConfiguration("Google Chat API enabled with a configured Chat app for the Cloud project."),
    TelegramContentUseTerms("Telegram API terms restrict AI use of content. Keep Telegram-derived AI off until the use and consent model is qualified."),
    TelegramBotToken("A BotFather token stored per account. MINI never ships a shared bot token."),
    XApiEntitlementAndBudget("X developer app with DM scopes, pay-per-use credits and per-endpoint limits confirmed."),
    BusinessServerDeployment("A separately described server mode for webhooks and confidential tokens. The publisher is not reused."),
    PartnerAccess("Written partner/product access from the provider."),
    NotificationListenerAccess("User grants Android notification access and selects this source."),
}

data class CapabilityGrant(
    val capability: Capability,
    /** Any one of these scope sets satisfies the capability (providers often accept a broader scope). */
    val anyOfScopes: List<Set<String>>,
)

data class ConnectorDescriptor(
    val provider: Provider,
    val routeName: String,
    val accountKind: RouteAccountKind,
    val route: ConnectorRoute,
    val status: ImplementationStatus,
    val grants: List<CapabilityGrant>,
    val gates: Map<Capability, Set<ReleaseGate>>,
    /** Gates that apply to the whole route (connect is blocked until cleared). */
    val routeGates: Set<ReleaseGate>,
    val coverageNote: String,
    val officialDocs: List<String>,
    val reviewedOn: String = REVIEWED_ON,
) {
    val implementedCapabilities: Set<Capability> get() = grants.map { it.capability }.toSet()

    companion object { const val REVIEWED_ON = "2026-10-10" }
}

/** Evidence a project owner records once a gate is satisfied (for one account or the whole app). */
data class GateEvidence(val gate: ReleaseGate, val evidence: String) {
    init { require(evidence.isNotBlank()) { "A cleared gate needs written evidence" } }
}

data class CapabilitySnapshot(
    val enabled: Set<Capability>,
    /** Capability -> why it is off (missing scope or uncleared gates). Shown to the user. */
    val disabled: Map<Capability, String>,
)

object CapabilityResolver {
    fun resolve(
        descriptor: ConnectorDescriptor,
        grantedScopes: Set<String>,
        clearedGates: Collection<GateEvidence>,
        accountState: ConnectionState,
    ): CapabilitySnapshot {
        val cleared = clearedGates.map { it.gate }.toSet()
        val disabled = linkedMapOf<Capability, String>()
        val enabled = mutableSetOf<Capability>()
        val routeBlocked = descriptor.routeGates - cleared
        Capability.entries.forEach { capability ->
            val grant = descriptor.grants.firstOrNull { it.capability == capability }
            val reason = when {
                grant == null -> "Not supported on ${descriptor.routeName}"
                accountState !in USABLE_STATES -> "Account is $accountState"
                routeBlocked.isNotEmpty() -> "Blocked: " + routeBlocked.joinToString { it.name }
                grant.anyOfScopes.none { grantedScopes.containsAll(it) } ->
                    "Permission not granted: " + grant.anyOfScopes.first().joinToString(" ")
                else -> (descriptor.gates[capability].orEmpty() - cleared)
                    .takeIf { it.isNotEmpty() }?.let { "Blocked: " + it.joinToString { g -> g.name } }
            }
            if (reason == null) enabled += capability else disabled[capability] = reason
        }
        return CapabilitySnapshot(enabled, disabled)
    }

    private val USABLE_STATES = setOf(ConnectionState.Authorized, ConnectionState.Healthy, ConnectionState.Syncing)
}

/**
 * Route registry for the 15 requested providers. Scope strings and endpoints were rechecked
 * against official documentation on [ConnectorDescriptor.REVIEWED_ON]; see docs/CONNECTORS.md.
 */
object ConnectorCatalog {
    const val TELEGRAM_BOT_SCOPE = "telegram.bot"

    private const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
    private const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    private const val GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
    private const val CHAT = "https://www.googleapis.com/auth/chat."
    private val sendGates = setOf(ReleaseGate.SendQualification)

    val gmail = ConnectorDescriptor(
        provider = Provider.GMAIL, routeName = "Gmail REST API", accountKind = RouteAccountKind.PersonalInbox,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf(GMAIL_READ), setOf(GMAIL_MODIFY))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf(GMAIL_READ), setOf(GMAIL_MODIFY))),
            CapabilityGrant(Capability.IncrementalSync, listOf(setOf(GMAIL_READ), setOf(GMAIL_MODIFY))),
            CapabilityGrant(Capability.Reply, listOf(setOf(GMAIL_SEND), setOf(GMAIL_MODIFY))),
            CapabilityGrant(Capability.SendNew, listOf(setOf(GMAIL_SEND), setOf(GMAIL_MODIFY))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf(GMAIL_READ), setOf(GMAIL_MODIFY))),
        ),
        gates = mapOf(Capability.Reply to sendGates, Capability.SendNew to sendGates),
        routeGates = emptySet(),
        coverageNote = "Full mailbox via official API. Incremental sync uses historyId; an expired cursor triggers a bounded resync with a recorded gap.",
        officialDocs = listOf(
            "https://developers.google.com/workspace/gmail/api/reference/rest",
            "https://developers.google.com/workspace/gmail/api/guides/sync",
            "https://developers.google.com/workspace/gmail/api/auth/scopes",
        ),
    )

    val outlook = ConnectorDescriptor(
        provider = Provider.OUTLOOK, routeName = "Microsoft Graph mail (delegated)", accountKind = RouteAccountKind.PersonalInbox,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf("Mail.Read"), setOf("Mail.ReadWrite"))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf("Mail.Read"), setOf("Mail.ReadWrite"))),
            CapabilityGrant(Capability.IncrementalSync, listOf(setOf("Mail.Read"), setOf("Mail.ReadWrite"))),
            CapabilityGrant(Capability.Reply, listOf(setOf("Mail.Send"))),
            CapabilityGrant(Capability.SendNew, listOf(setOf("Mail.Send"))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf("Mail.Read"), setOf("Mail.ReadWrite"))),
        ),
        gates = mapOf(Capability.Reply to sendGates, Capability.SendNew to sendGates),
        routeGates = setOf(ReleaseGate.MicrosoftAppRegistration),
        coverageNote = "Inbox folder delta (per-folder cursor). Other folders are not synced yet. sendMail/reply return 202: accepted, not delivered.",
        officialDocs = listOf(
            "https://learn.microsoft.com/en-us/graph/api/message-delta?view=graph-rest-1.0",
            "https://learn.microsoft.com/en-us/graph/api/user-sendmail?view=graph-rest-1.0",
            "https://learn.microsoft.com/en-us/graph/api/message-reply?view=graph-rest-1.0",
        ),
    )

    val teams = ConnectorDescriptor(
        provider = Provider.TEAMS, routeName = "Microsoft Graph Teams chats (delegated)", accountKind = RouteAccountKind.WorkWorkspace,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf("Chat.Read"), setOf("Chat.ReadWrite"))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf("Chat.Read"), setOf("Chat.ReadWrite"))),
            CapabilityGrant(Capability.Reply, listOf(setOf("ChatMessage.Send"), setOf("Chat.ReadWrite"))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf("Chat.Read"), setOf("Chat.ReadWrite"))),
        ),
        gates = mapOf(Capability.Reply to sendGates),
        routeGates = setOf(ReleaseGate.MicrosoftAppRegistration, ReleaseGate.TenantAdminConsent),
        coverageNote = "1:1 and group chats only, bounded recent pages per chat. Channels are not synced. Work/school tenants only.",
        officialDocs = listOf(
            "https://learn.microsoft.com/en-us/graph/api/chat-list?view=graph-rest-1.0",
            "https://learn.microsoft.com/en-us/graph/api/chat-list-messages?view=graph-rest-1.0",
            "https://learn.microsoft.com/en-us/graph/api/chatmessage-post?view=graph-rest-1.0",
        ),
    )

    val slack = ConnectorDescriptor(
        provider = Provider.SLACK, routeName = "Slack Web API (user token, PKCE)", accountKind = RouteAccountKind.WorkWorkspace,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf("im:history"), setOf("channels:history"), setOf("groups:history"), setOf("mpim:history"))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf("im:history"), setOf("channels:history"), setOf("groups:history"), setOf("mpim:history"))),
            CapabilityGrant(Capability.Reply, listOf(setOf("chat:write"))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf("im:history"), setOf("channels:history"))),
        ),
        gates = mapOf(Capability.Reply to sendGates),
        routeGates = setOf(ReleaseGate.SlackAppInstallation),
        coverageNote = "Conversations the signed-in user can see, per granted conversation type. History limits for non-Marketplace apps can restrict backfill. PKCE refresh tokens expire after 30 days.",
        officialDocs = listOf(
            "https://docs.slack.dev/authentication/using-pkce/",
            "https://docs.slack.dev/reference/methods/conversations.history/",
            "https://docs.slack.dev/reference/methods/chat.postMessage/",
        ),
    )

    val googleChat = ConnectorDescriptor(
        provider = Provider.GOOGLE_CHAT, routeName = "Google Chat API (user auth)", accountKind = RouteAccountKind.WorkWorkspace,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf("${CHAT}spaces.readonly", "${CHAT}messages.readonly"))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf("${CHAT}messages.readonly"), setOf("${CHAT}messages"))),
            CapabilityGrant(Capability.Reply, listOf(setOf("${CHAT}messages.create"), setOf("${CHAT}messages"))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf("${CHAT}messages.readonly"), setOf("${CHAT}messages"))),
        ),
        gates = mapOf(Capability.Reply to sendGates),
        routeGates = setOf(ReleaseGate.GoogleChatAppConfiguration),
        coverageNote = "Spaces the user belongs to, bounded recent messages per space. History-off spaces and retention limit coverage.",
        officialDocs = listOf(
            "https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/list",
            "https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/create",
            "https://developers.google.com/workspace/chat/authenticate-authorize-chat-user",
        ),
    )

    val telegramBot = ConnectorDescriptor(
        provider = Provider.TELEGRAM, routeName = "Telegram Bot API", accountKind = RouteAccountKind.Bot,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf(TELEGRAM_BOT_SCOPE))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf(TELEGRAM_BOT_SCOPE))),
            CapabilityGrant(Capability.IncrementalSync, listOf(setOf(TELEGRAM_BOT_SCOPE))),
            CapabilityGrant(Capability.Reply, listOf(setOf(TELEGRAM_BOT_SCOPE))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf(TELEGRAM_BOT_SCOPE))),
        ),
        gates = mapOf(
            Capability.Reply to sendGates,
            Capability.LocalAiDrafting to setOf(ReleaseGate.TelegramContentUseTerms),
        ),
        routeGates = setOf(ReleaseGate.TelegramBotToken),
        coverageNote = "Only chats the bot is in, from the time it was added. Never the user's personal Telegram history. Personal client (TDLib) is deferred.",
        officialDocs = listOf("https://core.telegram.org/bots/api", "https://core.telegram.org/api/terms"),
    )

    val xDirectMessages = ConnectorDescriptor(
        provider = Provider.TWITTER, routeName = "X API v2 Direct Messages (OAuth 2.0 PKCE)", accountKind = RouteAccountKind.PersonalInbox,
        route = ConnectorRoute.OfficialProviderApi, status = ImplementationStatus.AdapterImplemented,
        grants = listOf(
            CapabilityGrant(Capability.ListHistory, listOf(setOf("dm.read", "tweet.read", "users.read"))),
            CapabilityGrant(Capability.ReadBody, listOf(setOf("dm.read", "tweet.read", "users.read"))),
            CapabilityGrant(Capability.Reply, listOf(setOf("dm.write", "tweet.read", "users.read"))),
            CapabilityGrant(Capability.LocalAiDrafting, listOf(setOf("dm.read"))),
        ),
        gates = mapOf(Capability.Reply to sendGates),
        routeGates = setOf(ReleaseGate.XApiEntitlementAndBudget),
        coverageNote = "DM events from the last 30 days only; paid per use. Encrypted chat features are not covered.",
        officialDocs = listOf(
            "https://docs.x.com/x-api/direct-messages/lookup/introduction",
            "https://docs.x.com/x-api/direct-messages/manage/introduction",
            "https://docs.x.com/x-api/getting-started/pricing",
        ),
    )

    private fun notificationOnly(provider: Provider, name: String, note: String, docs: List<String> = emptyList()) = ConnectorDescriptor(
        provider = provider, routeName = "$name notifications + open in app", accountKind = RouteAccountKind.NotificationObserver,
        route = ConnectorRoute.NotificationOnly, status = ImplementationStatus.NotificationFallbackOnly,
        grants = emptyList(), gates = emptyMap(), routeGates = setOf(ReleaseGate.NotificationListenerAccess),
        coverageNote = note,
        officialDocs = docs + "https://developer.android.com/reference/android/service/notification/NotificationListenerService",
    )

    private fun deferred(provider: Provider, name: String, kind: RouteAccountKind, gates: Set<ReleaseGate>, note: String, docs: List<String>) = ConnectorDescriptor(
        provider = provider, routeName = name, accountKind = kind, route = ConnectorRoute.OfficialProviderApi,
        status = ImplementationStatus.DeferredExternalRequirement, grants = emptyList(), gates = emptyMap(),
        routeGates = gates, coverageNote = note, officialDocs = docs,
    )

    private const val PERSONAL_NOTE = "No official personal-inbox API. Notifications only: may hide text, combine chats, and never include sent history. Replies open the app."

    val personalFallbacks = listOf(
        notificationOnly(Provider.WHATSAPP, "WhatsApp", PERSONAL_NOTE),
        notificationOnly(Provider.INSTAGRAM, "Instagram", PERSONAL_NOTE),
        notificationOnly(Provider.FACEBOOK, "Messenger", PERSONAL_NOTE),
        notificationOnly(Provider.LINE, "LINE", PERSONAL_NOTE),
        notificationOnly(Provider.LINKEDIN, "LinkedIn", "LinkedIn sign-in does not grant messaging. $PERSONAL_NOTE"),
        notificationOnly(Provider.SIGNAL, "Signal", "No sanctioned third-party Signal API. Redacted notifications stay redacted; disappearing content is not kept."),
        notificationOnly(Provider.DISCORD, "Discord", "User-token automation is forbidden. Bot route deferred. $PERSONAL_NOTE"),
        notificationOnly(Provider.GOOGLE_MESSAGES, "Google Messages", "No public personal RCS API. SMS role is a separate product decision."),
    )

    val businessRoutes = listOf(
        deferred(Provider.WHATSAPP, "WhatsApp Business Cloud API", RouteAccountKind.BusinessAccount,
            setOf(ReleaseGate.BusinessServerDeployment), "Business/customer messaging only; needs webhooks and server-held tokens.",
            listOf("https://developers.facebook.com/docs/whatsapp/cloud-api/")),
        deferred(Provider.INSTAGRAM, "Instagram professional messaging", RouteAccountKind.BusinessAccount,
            setOf(ReleaseGate.BusinessServerDeployment), "Professional accounts only; webhooks required.",
            listOf("https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/messaging-api")),
        deferred(Provider.FACEBOOK, "Messenger Platform (Page)", RouteAccountKind.BusinessAccount,
            setOf(ReleaseGate.BusinessServerDeployment), "Page conversations only; 24-hour reply window; webhooks required.",
            listOf("https://developers.facebook.com/docs/messenger-platform/")),
        deferred(Provider.LINE, "LINE Messaging API (Official Account)", RouteAccountKind.BusinessAccount,
            setOf(ReleaseGate.BusinessServerDeployment), "Official Account only; webhook signature verification needs the channel secret on a server.",
            listOf("https://developers.line.biz/en/reference/messaging-api/")),
        deferred(Provider.DISCORD, "Discord bot", RouteAccountKind.Bot,
            setOf(ReleaseGate.BusinessServerDeployment), "Bot sees only servers/channels it is in; Gateway socket and bot secret belong on a server.",
            listOf("https://docs.discord.com/developers/events/gateway")),
        deferred(Provider.LINKEDIN, "LinkedIn partner messaging", RouteAccountKind.BusinessAccount,
            setOf(ReleaseGate.PartnerAccess), "Only with written partner access.",
            listOf("https://learn.microsoft.com/en-us/linkedin/compliance/compliance-api/overview")),
        deferred(Provider.TELEGRAM, "Telegram personal client (TDLib)", RouteAccountKind.PersonalInbox,
            setOf(ReleaseGate.TelegramContentUseTerms), "Needs MINI's api_id/api_hash, a native TDLib build and the AI-use qualification.",
            listOf("https://core.telegram.org/tdlib")),
    )

    val apiRoutes = listOf(gmail, outlook, teams, slack, googleChat, telegramBot, xDirectMessages)
    val all: List<ConnectorDescriptor> = apiRoutes + personalFallbacks + businessRoutes

    fun routesFor(provider: Provider): List<ConnectorDescriptor> = all.filter { it.provider == provider }

    /** The implemented API descriptor for a connected account's provider, if any. */
    fun apiRouteFor(provider: Provider): ConnectorDescriptor? =
        apiRoutes.firstOrNull { it.provider == provider }
}
