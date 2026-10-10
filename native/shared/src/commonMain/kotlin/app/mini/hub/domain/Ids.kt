package app.mini.hub.domain

/**
 * Identifiers are always scoped by provider and local account (architecture section 6).
 * A raw provider id such as a Gmail message id is never used as a global key.
 */
enum class Provider(val wire: String) {
    GMAIL("gmail"), OUTLOOK("outlook"), TEAMS("teams"), WHATSAPP("whatsapp"), WHATSAPP_BUSINESS("whatsapp_business"),
    /**
     * Facebook Messenger. The wire value stays "facebook" so existing account keys, scoped IDs and
     * stored observations keep resolving. Explicit migration decision: no rename, no key merge.
     */
    FACEBOOK("facebook"), INSTAGRAM("instagram"), LINKEDIN("linkedin"),
    /** X (formerly Twitter). Wire value stays "twitter" for the same reason as [FACEBOOK]. */
    TWITTER("twitter"), SLACK("slack"), WHOOP("whoop"), SYSTEM("system"), APP("app"),
    DISCORD("discord"), GOOGLE_CHAT("google_chat"), GOOGLE_MESSAGES("google_messages"),
    LINE("line"), SIGNAL("signal"), TELEGRAM("telegram");

    companion object {
        fun fromWire(value: String): Provider = entries.firstOrNull { it.wire == value } ?: APP
    }
}

/** Local account identity. [localId] is generated locally; [provider] is part of the identity. */
data class AccountId(val provider: Provider, val localId: String) {
    init {
        require(localId.isNotBlank()) { "AccountId.localId must not be blank" }
        require(!localId.contains('|')) { "AccountId.localId must not contain '|'" }
    }

    val key: String get() = "${provider.wire}|$localId"
    override fun toString(): String = key

    companion object {
        fun parse(key: String): AccountId {
            val (p, id) = key.split('|', limit = 2)
            return AccountId(Provider.fromWire(p), id)
        }
    }
}

/** An id that only has meaning inside one account, e.g. a provider thread id or a local observation id. */
data class ScopedId(val account: AccountId, val kind: Kind, val value: String) {
    enum class Kind { CONVERSATION, MESSAGE, OBSERVATION, PARTICIPANT, DRAFT, APPROVAL, COMMAND, FACT, ACTION_ITEM }

    init {
        require(value.isNotBlank()) { "ScopedId.value must not be blank" }
    }

    val key: String get() = "${account.key}|${kind.name.lowercase()}|$value"
    override fun toString(): String = key

    companion object {
        fun parse(key: String): ScopedId {
            val parts = key.split('|', limit = 4)
            require(parts.size == 4) { "Malformed scoped id: $key" }
            return ScopedId(
                AccountId(Provider.fromWire(parts[0]), parts[1]),
                Kind.valueOf(parts[2].uppercase()),
                parts[3],
            )
        }
    }
}

typealias ConversationId = ScopedId
typealias MessageId = ScopedId
typealias ObservationId = ScopedId

/**
 * A channel identity (email address, phone number, platform user id) scoped to an account.
 * [displayName] is presentation only and never participates in equality: two "Rahul" contacts
 * with different authoritative identifiers are different participants (architecture section 6).
 */
class Participant(val id: ScopedId, val displayName: String) {
    init {
        require(id.kind == ScopedId.Kind.PARTICIPANT) { "Participant id must be PARTICIPANT kind" }
    }

    override fun equals(other: Any?): Boolean = other is Participant && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Participant(${id.key}, '$displayName')"
}

/** Generates unique local ids. Injected so tests are deterministic and production uses randomness. */
fun interface IdGenerator {
    fun next(prefix: String): String
}
