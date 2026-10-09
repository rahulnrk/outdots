package app.mini.hub.android.connector

import android.content.Context
import android.util.Base64
import app.mini.hub.connector.AccessTokenStore
import app.mini.hub.connector.ConnectedAccount
import app.mini.hub.connector.ConnectedAccountStore
import app.mini.hub.connector.StoredAccessToken
import app.mini.hub.domain.AccountId
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account metadata is durable, while token bytes are separately encrypted with Android Keystore. */
class AndroidConnectedAccountStore(context: Context) : ConnectedAccountStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun list(): List<ConnectedAccount> = synchronized(this) {
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { decode(prefs.getString(key(it), null)) }
    }

    override fun get(id: AccountId): ConnectedAccount? = synchronized(this) { decode(prefs.getString(key(id.key), null)) }

    override fun upsert(account: ConnectedAccount) = synchronized(this) {
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet().apply { add(account.id.key) }
        prefs.edit().putStringSet(KEY_IDS, ids).putString(key(account.id.key), encode(account)).apply()
    }

    override fun remove(id: AccountId): Boolean = synchronized(this) {
        val existed = prefs.contains(key(id.key))
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet().apply { remove(id.key) }
        prefs.edit().putStringSet(KEY_IDS, ids).remove(key(id.key)).apply()
        existed
    }

    private fun encode(a: ConnectedAccount): String = listOf(
        a.id.provider.wire, a.id.localId, a.providerAccountId, a.displayName.orEmpty(), a.accountType.name,
        a.route.name, a.state.name, a.grantedScopes.sorted().joinToString(","), a.lastSuccessfulSyncMillis?.toString().orEmpty(),
        a.userActionRequired.orEmpty(),
    ).joinToString("\u001f") { Base64.encodeToString(it.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) }

    private fun decode(raw: String?): ConnectedAccount? = runCatching {
        val values = raw?.split('\u001f')?.map { String(Base64.decode(it, Base64.DEFAULT), StandardCharsets.UTF_8) } ?: return null
        if (values.size != 10) return null
        ConnectedAccount(
            id = AccountId(app.mini.hub.domain.Provider.fromWire(values[0]), values[1]),
            providerAccountId = values[2], displayName = values[3].ifBlank { null },
            accountType = app.mini.hub.connector.AccountType.valueOf(values[4]),
            route = app.mini.hub.connector.ConnectorRoute.valueOf(values[5]),
            state = app.mini.hub.connector.ConnectionState.valueOf(values[6]),
            grantedScopes = values[7].split(',').filter { it.isNotBlank() }.toSet(),
            lastSuccessfulSyncMillis = values[8].toLongOrNull(), userActionRequired = values[9].ifBlank { null },
        )
    }.getOrNull()

    private fun key(accountKey: String) = "account_${Base64.encodeToString(accountKey.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)}"

    companion object { private const val FILE = "mini_connected_accounts"; private const val KEY_IDS = "account_ids" }
}

class AndroidKeystoreAccessTokenStore(context: Context) : AccessTokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(accountId: AccountId): StoredAccessToken? = synchronized(this) {
        val encoded = prefs.getString(key(accountId), null) ?: return null
        runCatching {
            val parts = encoded.split(':', limit = 2)
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val ciphertext = Base64.decode(parts[1], Base64.DEFAULT)
            val plain = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            }.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            val delimiter = plain.indexOf('|')
            StoredAccessToken(plain.substring(delimiter + 1), plain.substring(0, delimiter).toLongOrNull())
        }.getOrNull()
    }

    override fun put(accountId: AccountId, token: StoredAccessToken) = synchronized(this) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val plain = "${token.expiresAtMillis ?: ""}|${token.value}".toByteArray(StandardCharsets.UTF_8)
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
        prefs.edit().putString(key(accountId), payload).apply()
    }

    override fun remove(accountId: AccountId) {
        synchronized(this) { prefs.edit().remove(key(accountId)).apply() }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }

    private fun key(id: AccountId) = "token_${Base64.encodeToString(id.key.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)}"

    companion object {
        private const val FILE = "mini_connector_tokens"
        private const val KEY_ALIAS = "mini_connector_access_tokens_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
