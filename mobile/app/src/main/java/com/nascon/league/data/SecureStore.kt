package com.nascon.league.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable

@Serializable
private data class SavedLogin(val session: String, val token: String, val salt: String, val proof: String)

/**
 * Small settings store. The JWT is encrypted with an AES key that lives in the
 * Android Keystore (the key never leaves secure hardware), then saved in prefs.
 */
class SecureStore(context: Context, defaultServerUrl: String) {
    private val prefs = context.getSharedPreferences("league_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "league_token_key"

    var serverUrl: String = prefs.getString("server_url", null) ?: defaultServerUrl
        set(value) {
            field = value
            prefs.edit().putString("server_url", value).apply()
        }

    var wirelessUrl: String
        get() = prefs.getString("wireless_url", null) ?: defaultWirelessUrl
        set(value) { prefs.edit().putString("wireless_url", value).apply() }
    private val defaultWirelessUrl = defaultServerUrl.takeUnless {
        java.net.URI(it).host in setOf("127.0.0.1", "localhost", "10.0.2.2")
    }.orEmpty()

    // Endpoint aliases share account caches when USB switches to the saved LAN address.
    var serverIdentity: String
        get() = prefs.getString("server_identity", null) ?: serverUrl.also {
            prefs.edit().putString("server_identity", it).apply()
        }
        set(value) { prefs.edit().putString("server_identity", value).apply() }

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    /** Last signed-in user + tenant as JSON, so the app opens offline after a restart. */
    var sessionJson: String?
        get() = prefs.getString("session", null)
        set(value) = prefs.edit().putString("session", value).apply()


    var token: String? = readToken()
        private set

    fun saveToken(value: String?) {
        token = value
        if (value == null) {
            prefs.edit().remove("token").apply()
            return
        }
        prefs.edit().putString("token", encrypt(value)).apply()
    }

    /** Separate from the UI session: only pre-existing queued actions use this credential. */
    fun rememberOutboxToken(owner: String, token: String) {
        prefs.edit().putString("outbox:$owner", encrypt(token)).commit()
    }

    fun outboxToken(owner: String): String? = runCatching {
        prefs.getString("outbox:$owner", null)?.let(::decrypt) ?: recoverLegacyQueueToken(owner)
    }.getOrNull()

    /** Upgrade already queued work from builds that only saved encrypted offline logins. */
    private fun recoverLegacyQueueToken(owner: String): String? {
        if (!queueBelongsToServer(owner, serverIdentity)) return null
        return prefs.all.entries.asSequence().filter { it.key.startsWith("account:$serverIdentity:") }
            .mapNotNull { (_, value) -> runCatching {
                val login = AppJson.decodeFromString(SavedLogin.serializer(), decrypt(value as String))
                val account = AppJson.decodeFromString(Session.serializer(), login.session)
                if ("$serverIdentity|${account.user.user_id}" == owner) login.token else null
            }.getOrNull() }.firstOrNull()
    }

    fun forgetOutboxToken(owner: String) {
        prefs.edit().remove("outbox:$owner").commit()
    }

    /** Re-login offline only after this account has authenticated online on this installation. */
    fun rememberLogin(email: String, password: String, session: String, token: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saved = SavedLogin(session, token, Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(proof(password, salt), Base64.NO_WRAP))
        prefs.edit().putString("account:$serverIdentity:$email", encrypt(AppJson.encodeToString(SavedLogin.serializer(), saved))).apply()
    }

    fun offlineLogin(email: String, password: String): Pair<String, String>? = runCatching {
        val encoded = prefs.getString("account:$serverIdentity:$email", null) ?: return null
        val saved = AppJson.decodeFromString(SavedLogin.serializer(), decrypt(encoded))
        val salt = Base64.decode(saved.salt, Base64.NO_WRAP)
        if (!MessageDigest.isEqual(proof(password, salt), Base64.decode(saved.proof, Base64.NO_WRAP))) return null
        saved.session to saved.token
    }.getOrNull()

    private fun proof(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val packed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)))
    }

    private fun readToken(): String? {
        val stored = prefs.getString("token", null) ?: return null
        return runCatching {
            decrypt(stored)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
