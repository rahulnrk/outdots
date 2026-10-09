package app.mini.hub.connector

import app.mini.hub.domain.Provider

/**
 * Read-only first connector. It uses the official Gmail REST API with a caller-supplied OAuth
 * access token. OAuth UI, client identity and refresh-token storage deliberately stay platform
 * specific. This adapter never falls back to notification data or a third-party bridge.
 */
class GmailApiConnector(
    private val apiBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
) : AccountConnector {
    override val provider: Provider = Provider.GMAIL
    override val route: ConnectorRoute = ConnectorRoute.OfficialProviderApi

    override fun discoverAccount(
        accessToken: StoredAccessToken,
        transport: ProviderHttpTransport,
    ): ProviderAccountIdentity {
        val json = get(transport, "$apiBase/profile", accessToken)
        val email = requiredJsonString(json, "emailAddress")
        return ProviderAccountIdentity(Provider.GMAIL, email.lowercase(), email, AccountType.GoogleConsumer)
    }

    override fun sync(
        account: ConnectedAccount,
        accessToken: StoredAccessToken,
        checkpoint: SyncCheckpoint,
        transport: ProviderHttpTransport,
        maxMessages: Int,
    ): SyncResult {
        require(account.id.provider == Provider.GMAIL)
        require(maxMessages in 1..100)
        val query = buildString {
            append("$apiBase/messages?maxResults=")
            append(maxMessages)
            if (!checkpoint.cursor.isNullOrBlank()) append("&pageToken=").append(urlEncode(checkpoint.cursor))
        }
        val listJson = get(transport, query, accessToken)
        val ids = listJson.objects("messages").take(maxMessages)
        val messages = ids.mapNotNull { objectJson ->
            val id = optionalJsonString(objectJson, "id") ?: return@mapNotNull null
            val thread = optionalJsonString(objectJson, "threadId") ?: id
            val detail = get(transport, "$apiBase/messages/$id?format=metadata", accessToken)
            ProviderMessage(
                providerMessageId = id,
                conversationId = thread,
                sender = header(detail, "From") ?: "Unknown sender",
                subject = header(detail, "Subject"),
                snippet = optionalJsonString(detail, "snippet").orEmpty(),
                occurredAtMillis = optionalJsonString(detail, "internalDate")?.toLongOrNull() ?: 0L,
            )
        }
        val next = optionalJsonString(listJson, "nextPageToken")
        return SyncResult(
            messages = messages,
            next = checkpoint.copy(cursor = next, processedCount = checkpoint.processedCount + messages.size),
            state = ConnectionState.Healthy,
        )
    }

    private fun get(transport: ProviderHttpTransport, url: String, token: StoredAccessToken): String {
        val result = transport.get(url, mapOf("Authorization" to "Bearer ${token.value}"))
        val reason = Regex("\"reason\"\\s*:\\s*\"([A-Za-z_]+)\"").find(result.body)?.groupValues?.get(1)
        if (result.statusCode == 401) throw ConnectorHttpException(401, "Gmail authorization expired or revoked", reason)
        if (result.statusCode == 403) {
            // Google's reason code (e.g. accessNotConfigured, insufficientPermissions) is non-sensitive and
            // distinguishes a disabled Gmail API from a consent screen where the Gmail scope was unticked.
            val hint = when (reason) {
                "accessNotConfigured", "SERVICE_DISABLED" -> "Gmail API is not enabled for this Google Cloud project"
                "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> "Gmail read permission was not granted on the consent screen"
                else -> "Gmail permission denied for this account"
            }
            throw ConnectorHttpException(403, "$hint (${reason ?: "unknown"})", reason)
        }
        if (result.statusCode == 429 || result.statusCode >= 500) {
            throw ConnectorHttpException(result.statusCode, "Gmail temporarily unavailable; retry with backoff")
        }
        if (result.statusCode !in 200..299) throw ConnectorHttpException(result.statusCode, "Gmail request failed")
        return result.body
    }
}

private fun String.objects(field: String): List<String> {
    val marker = "\"$field\""
    val start = indexOf(marker).takeIf { it >= 0 } ?: return emptyList()
    val open = indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    val close = matchingBracket(open).takeIf { it >= 0 } ?: return emptyList()
    // Walk top-level objects by bracket depth. Splitting on a literal "},{" breaks on
    // pretty-printed responses ("},\n  {"), which Gmail returns, and silently keeps only one item.
    val result = mutableListOf<String>()
    var i = open + 1
    while (i < close) {
        val c = this[i]
        if (c == '{') {
            val end = matchingBracket(i)
            if (end < 0 || end > close) break
            result += substring(i, end + 1)
            i = end + 1
        } else {
            i++
        }
    }
    return result
}

private fun String.matchingBracket(open: Int): Int {
    var depth = 0
    var quoted = false
    var escaped = false
    for (i in open until length) {
        val c = this[i]
        if (escaped) { escaped = false; continue }
        if (c == '\\' && quoted) { escaped = true; continue }
        if (c == '"') quoted = !quoted
        if (quoted) continue
        if (c == '[' || c == '{') depth++
        if (c == ']' || c == '}') {
            depth--
            if (depth == 0) return i
        }
    }
    return -1
}

private fun String.jsonString(name: String): String? {
    val regex = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
    return regex.find(this)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
}

private fun requiredJsonString(json: String, name: String): String =
    optionalJsonString(json, name) ?: throw ConnectorHttpException(200, "Gmail response omitted $name")

private fun optionalJsonString(json: String, name: String): String? = json.jsonString(name)

private fun header(json: String, name: String): String? {
    val objects = json.objects("headers")
    return objects.firstNotNullOfOrNull { objectJson ->
        if (optionalJsonString(objectJson, "name").equals(name, ignoreCase = true)) optionalJsonString(objectJson, "value") else null
    }
}

private fun urlEncode(value: String): String = value.replace(" ", "%20").replace("+", "%2B")
