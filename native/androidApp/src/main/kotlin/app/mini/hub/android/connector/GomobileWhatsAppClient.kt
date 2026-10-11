package app.mini.hub.android.connector

import app.mini.hub.action.ExactActionPayload
import app.mini.hub.connector.MessageDirection
import app.mini.hub.connector.ProviderMessage
import app.mini.hub.connector.SendOutcome
import app.mini.hub.connector.UnprovisionedWhatsAppClient
import app.mini.hub.connector.WhatsAppPairing
import app.mini.hub.connector.WhatsAppPhoneClient
import app.mini.hub.connector.WhatsAppReconnect
import app.mini.hub.connector.WhatsAppSessionEnvelope

/**
 * Reflective JNI client for the gomobile `waruntime.Runtime`. Compiles without the AAR;
 * load() returns null when the native library is not packaged.
 */
class GomobileWhatsAppClient private constructor(private val native: Any) : WhatsAppPhoneClient {
    override fun startPairing(): WhatsAppPairing {
        return try {
            invoke("startPairing")
            pollPairing()
        } catch (error: Throwable) {
            WhatsAppPairing.Unavailable(error.message ?: UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
        }
    }

    override fun startPhonePairing(phoneE164: String): WhatsAppPairing {
        return try {
            invoke("startPhonePairing", phoneE164)
            pollPairing()
        } catch (error: Throwable) {
            WhatsAppPairing.Unavailable(error.message ?: UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
        }
    }

    override fun pollPairing(): WhatsAppPairing {
        val state = string("pairingState")
        return when (state) {
            "code" -> {
                val code = string("pairingCode")
                if (code.isBlank()) WhatsAppPairing.Unavailable("qr_pending")
                else WhatsAppPairing.PhoneCode(code)
            }
            "qr" -> {
                val qr = string("qrPayload")
                if (qr.isBlank()) WhatsAppPairing.Unavailable("qr_pending")
                else try {
                    WhatsAppPairing.QrReady(qr, long("qrExpiresAtMillis"))
                } catch (_: IllegalArgumentException) {
                    WhatsAppPairing.Unavailable("qr_payload_rejected")
                }
            }
            "linked" -> {
                val phone = linkedPhoneE164() ?: return WhatsAppPairing.Unavailable("linked_without_phone")
                val snap = persistSnapshot() ?: return WhatsAppPairing.Unavailable("linked_without_snapshot")
                WhatsAppPairing.Linked(phone, WhatsAppSessionEnvelope(snap))
            }
            "cancelled" -> WhatsAppPairing.Cancelled
            "error" -> WhatsAppPairing.Unavailable(string("lastError").ifBlank { "whatsapp_pair_failed" })
            else -> WhatsAppPairing.Unavailable(UnprovisionedWhatsAppClient.RUNTIME_NOT_PACKAGED)
        }
    }

    override fun cancelPairing() {
        invoke("cancelPairing")
    }

    override fun restoreAndReconnect(envelope: WhatsAppSessionEnvelope): WhatsAppReconnect {
        return try {
            invoke("restore", envelope.ciphertext)
            invoke("reconnect")
            val phone = linkedPhoneE164() ?: return WhatsAppReconnect.Unavailable("whatsapp_session_missing")
            WhatsAppReconnect.Restored(phone)
        } catch (error: Throwable) {
            WhatsAppReconnect.Unavailable(error.message ?: "whatsapp_reconnect_failed")
        }
    }

    override fun linkedPhoneE164(): String? = string("linkedPhone").ifBlank { null }

    override fun send(payload: ExactActionPayload): SendOutcome {
        val chat = chatJid(payload)
        if (chat.isBlank()) return SendOutcome.Rejected("whatsapp_missing_chat")
        return try {
            val stanza = invoke("sendText", chat, payload.body) as? String
                ?: return SendOutcome.Rejected("whatsapp_send_failed")
            SendOutcome.Accepted("whatsapp:$chat:$stanza")
        } catch (error: Throwable) {
            SendOutcome.Rejected(error.message ?: "whatsapp_send_failed")
        }
    }

    override fun drainInbound(): List<ProviderMessage> {
        val raw = try {
            invoke("drainInboundJSON") as? String ?: "[]"
        } catch (_: Throwable) {
            return emptyList()
        }
        return parseInbound(raw)
    }

    override fun conversation(chatJid: String): List<ProviderMessage> {
        val raw = try {
            invoke("conversationJSON", chatJid) as? String ?: "[]"
        } catch (_: Throwable) {
            return emptyList()
        }
        return parseInbound(raw)
    }

    override fun persistSnapshot(): ByteArray? = try {
        invoke("snapshot") as? ByteArray
    } catch (_: Throwable) {
        null
    }

    override fun logout() {
        runCatching { invoke("logout") }
    }

    override fun unencryptedSessionFiles(): List<String> =
        string("unencryptedFiles").lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun mediaThumbnail(id: String): ByteArray? = runCatching { invoke("mediaThumbnail", id) as? ByteArray }.getOrNull()

    fun downloadMedia(id: String, dir: String): String? =
        runCatching { invoke("downloadMedia", id, dir) as? String }
            .onFailure { lastMediaError = it.message ?: it.javaClass.simpleName }
            .getOrNull()

    @Volatile var lastMediaError: String? = null

    fun searchContacts(query: String): List<Pair<String, String>> = runCatching {
        val arr = org.json.JSONArray(invoke("searchContactsJSON", query) as? String ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("jid") to o.optString("name") } }
    }.getOrDefault(emptyList())

    private fun chatJid(payload: ExactActionPayload): String {
        val raw = payload.conversationId.trim().ifBlank { payload.recipients.firstOrNull().orEmpty() }
        val fromScoped = raw.substringAfterLast("|conversation|", missingDelimiterValue = "")
        return fromScoped.ifBlank { raw }
    }

    private fun string(method: String): String = (invoke(method) as? String).orEmpty()
    private fun long(method: String): Long = (invoke(method) as? Long) ?: 0L

    private fun invoke(method: String, vararg args: Any?): Any? {
        val types = args.map { arg ->
            when (arg) {
                is ByteArray -> ByteArray::class.java
                is String -> String::class.java
                else -> arg?.javaClass ?: Any::class.java
            }
        }.toTypedArray()
        val fn = native.javaClass.methods.first { it.name == method && it.parameterTypes.size == types.size }
        return try {
            fn.invoke(native, *args)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    companion object {
        fun load(watchDir: String): GomobileWhatsAppClient? {
            return try {
                runCatching { Class.forName("go.Seq") }
                val runtimeClass = listOf(
                    "app.mini.hub.wa.waruntime.Runtime",
                    "app.mini.hub.wa.Runtime",
                ).firstNotNullOfOrNull { name -> runCatching { Class.forName(name) }.getOrNull() }
                    ?: return null
                val instance = runtimeClass.methods.firstOrNull { it.name == "newRuntime" && it.parameterTypes.size == 1 }
                    ?.invoke(null, watchDir)
                    ?: runtimeClass.getConstructor(String::class.java).newInstance(watchDir)
                GomobileWhatsAppClient(instance)
            } catch (_: Throwable) {
                null
            }
        }

        internal fun parseInbound(raw: String): List<ProviderMessage> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            return runCatching { parseWithOrgJson(trimmed) }.getOrElse { parseLoose(trimmed) }
        }

        private fun parseWithOrgJson(raw: String): List<ProviderMessage> {
            val array = org.json.JSONArray(raw)
            val out = ArrayList<ProviderMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                addMessage(
                    out,
                    obj.optString("id"),
                    obj.optString("chat"),
                    obj.optString("sender"),
                    obj.optString("name"),
                    obj.optString("title"),
                    obj.optString("body"),
                    obj.optLong("ts", 0L),
                    obj.optBoolean("out", false),
                )
            }
            return out
        }

        private fun parseLoose(raw: String): List<ProviderMessage> {
            val out = mutableListOf<ProviderMessage>()
            val objects = raw.split("},{").map {
                it.trim().removePrefix("[").removePrefix("{").removeSuffix("]").removeSuffix("}")
            }
            for (obj in objects) {
                if (obj.isBlank()) continue
                fun field(name: String): String {
                    val key = "\"$name\""
                    val start = obj.indexOf(key)
                    if (start < 0) return ""
                    val colon = obj.indexOf(':', start + key.length)
                    val rest = obj.substring(colon + 1).trim()
                    return if (rest.startsWith('"')) rest.drop(1).substringBefore('"') else rest.substringBefore(',').trim()
                }
                addMessage(out, field("id"), field("chat"), field("sender"), field("name"), field("title"), field("body"), field("ts").toLongOrNull() ?: 0L, field("out") == "true")
            }
            return out
        }

        private fun addMessage(
            out: MutableList<ProviderMessage>,
            id: String,
            chat: String,
            sender: String,
            name: String,
            title: String,
            body: String,
            ts: Long,
            outbound: Boolean,
        ) {
            if (id.isBlank() || chat.isBlank() || body.isBlank()) return
            val display = displayName(sender, name)
            out += ProviderMessage(
                providerMessageId = id,
                conversationId = chat,
                sender = display,
                subject = displayName(chat, title),
                snippet = body.take(240),
                occurredAtMillis = ts,
                bodyText = body,
                direction = if (outbound) MessageDirection.Outbound else MessageDirection.Inbound,
                replyAnchor = id,
            )
        }

        internal fun displayName(sender: String, name: String): String {
            if (name.isNotBlank() && !name.contains("@lid")) return name
            val raw = sender.trim()
            if (raw.endsWith("@s.whatsapp.net")) return "+" + raw.substringBefore("@")
            if (raw.contains("@lid")) return "WhatsApp contact"
            return raw.ifBlank { "WhatsApp contact" }
        }
    }
}
