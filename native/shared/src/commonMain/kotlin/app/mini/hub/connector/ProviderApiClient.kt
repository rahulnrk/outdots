package app.mini.hub.connector

/**
 * Shared request edge for API adapters: bearer auth, HTTPS-only requests, uniform error
 * classification and Retry-After capture. Messages never include response bodies or tokens.
 */
class ProviderApiClient(
    private val providerName: String,
    private val transport: ProviderHttpTransport,
    private val token: StoredAccessToken,
    /** Extracts a short, non-content provider error code from an error body. */
    private val reasonOf: (JsonValue?) -> String? = ::defaultReason,
    /** Lets an adapter refine kinds (e.g. Gmail 404 on history = cursor invalid). */
    private val classify: (status: Int, reason: String?) -> ProviderErrorKind = { s, _ -> ConnectorHttpException.defaultKind(s) },
) {
    fun get(url: String): JsonValue = call(ProviderHttpRequest(HttpMethod.GET, url, auth()))

    fun postJson(url: String, body: Any?, extraHeaders: Map<String, String> = emptyMap()): JsonValue =
        call(ProviderHttpRequest(HttpMethod.POST, url, auth() + extraHeaders + ("Content-Type" to JSON), JsonWriter.write(body), JSON))

    fun putJson(url: String, body: Any?): JsonValue =
        call(ProviderHttpRequest(HttpMethod.PUT, url, auth() + ("Content-Type" to JSON), JsonWriter.write(body), JSON))

    fun postForm(url: String, form: Map<String, String>): JsonValue =
        call(ProviderHttpRequest(HttpMethod.POST, url, auth() + ("Content-Type" to FORM), formEncode(form), FORM))

    fun call(request: ProviderHttpRequest): JsonValue {
        val response = try {
            transport.execute(request)
        } catch (e: ConnectorHttpException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            // Network failure: for a write the provider may or may not have acted.
            throw ConnectorHttpException(0, "$providerName unreachable (${e::class.simpleName})", null, ProviderErrorKind.Transient)
        }
        val successful = response.statusCode in 200..299
        val emptyBodyAllowed = response.statusCode == 202 || response.statusCode == 204
        val body = response.body.trim()
        val json = if (body.isBlank()) null else JsonValue.parseOrNull(body)
        if (successful) {
            if (json != null) return json
            if (body.isBlank() && emptyBodyAllowed) return JsonValue.Obj(emptyMap())
            throw ConnectorHttpException(
                statusCode = response.statusCode,
                message = "$providerName ${request.redacted()} returned an invalid success body",
                reason = "invalid_success_body",
                kind = ProviderErrorKind.Protocol,
            )
        }
        val reason = reasonOf(json)
        throw ConnectorHttpException(
            statusCode = response.statusCode,
            message = "$providerName ${request.redacted()} failed: HTTP ${response.statusCode}${reason?.let { " ($it)" } ?: ""}",
            reason = reason,
            kind = classify(response.statusCode, reason),
            retryAfterSeconds = ConnectorHttpException.retryAfter(response),
        )
    }

    private fun auth(): Map<String, String> = mapOf("Authorization" to "Bearer ${token.value}")

    companion object {
        const val JSON = "application/json; charset=utf-8"
        const val FORM = "application/x-www-form-urlencoded"

        fun defaultReason(json: JsonValue?): String? {
            val error = json?.get("error") ?: return null
            // Google: {"error":{"status":"PERMISSION_DENIED","errors":[{"reason":"insufficientPermissions"}]}}
            error.path("errors")?.objects?.firstOrNull()?.str("reason")?.let { return it }
            error.str("status")?.let { return it }
            // Microsoft Graph: {"error":{"code":"ErrorAccessDenied"}}
            error.str("code")?.let { return it }
            // Slack/OAuth: {"error":"invalid_auth"}
            return error.string
        }
    }
}

fun urlEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-._~") append(ch)
        else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
    }
}

fun formEncode(values: Map<String, String>): String =
    values.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

private const val HEX = "0123456789ABCDEF"

/** RFC 4648 base64url without padding, as Gmail's `raw` field requires. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) append(ALPHABET[((b1 and 0xF) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) append(ALPHABET[b2 and 0x3F])
            i += 3
        }
    }

    /** Accepts base64url or standard base64, with or without padding. */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it == '=' || it.isWhitespace() }.replace('+', '-').replace('/', '_')
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid base64 character" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}
