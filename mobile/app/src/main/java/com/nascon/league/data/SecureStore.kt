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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString("token", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun readToken(): String? {
        val stored = prefs.getString("token", null) ?: return null
        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)))
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
