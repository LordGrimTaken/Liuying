package com.liuying.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal seal/open contract so the string wrapper is unit-testable without the Android Keystore. */
interface CipherPort {
    /** Returns iv + ciphertext (GCM tag included). Throws on failure. */
    fun seal(plain: ByteArray): ByteArray
    /** Inverse of [seal]. Throws on tamper, wrong key or truncated input. */
    fun open(sealed: ByteArray): ByteArray
}

/** Pure string layer over a [CipherPort]: versioned hex text in, text out, never throws (QA #40). */
class SecretCodec(private val port: CipherPort) {
    fun encrypt(value: String): String? = runCatching {
        PREFIX + hex(port.seal(value.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    /** Null means "could not be read": tampered, wrong key (Keystore wiped), malformed. Callers treat that as unset. */
    fun decrypt(stored: String): String? = runCatching {
        if (!stored.startsWith(PREFIX)) return null
        String(port.open(unhex(stored.removePrefix(PREFIX))), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val PREFIX = "v1:"
        // hex, not Base64: java.util.Base64 needs API 26 and minSdk is 24
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0)
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** Decides what the settings layer reads and writes for the API key. */
object ApiKeyStorage {
    /** Encrypted copy wins; a failed decrypt is unset (never falls back to a stale plain copy). */
    fun read(encrypted: String?, plain: String?, codec: SecretCodec?): String = when {
        encrypted != null -> codec?.decrypt(encrypted).orEmpty()
        else -> plain.orEmpty()
    }

    /** Value to store encrypted, or null to store nothing (blank key, or encryption failed: never fall back to plain). */
    fun sealForWrite(value: String, codec: SecretCodec): String? =
        if (value.isBlank()) null else codec.encrypt(value)
}

/** AES-256-GCM with a non-exportable Android Keystore key. */
class KeystoreBox(private val alias: String = "liuying_settings_v1") : CipherPort {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "truncated" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
