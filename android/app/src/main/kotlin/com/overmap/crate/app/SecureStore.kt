package com.overmap.crate.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.overmap.crate.core.SpotifyTokens
import com.overmap.crate.core.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (Spotify tokens, GitHub token) encrypted with an AES-GCM key that never leaves the Android
 * Keystore — the counterpart of the iOS Keychain. Values are stored in private SharedPreferences.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("crate.secure", Context.MODE_PRIVATE)

    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            return gen.generateKey()
        }

    fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV))
            String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun delete(name: String) = prefs.edit().remove(name).apply()

    private companion object {
        const val ALIAS = "crate.secure.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** Spotify tokens in [SecureStore]. Never log these. */
class SecureTokenStore(private val store: SecureStore) : TokenStore {
    override fun load(): SpotifyTokens? =
        store.read("spotify.tokens")?.let { runCatching { SpotifyTokens.fromJson(it) }.getOrNull() }

    override fun save(tokens: SpotifyTokens) = store.write("spotify.tokens", tokens.toJson())

    override fun clear() = store.delete("spotify.tokens")
}
