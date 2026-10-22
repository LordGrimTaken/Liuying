package com.liuying.app.data.settings

import com.liuying.app.ui.player.logic.BackgroundAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretCodecTest {
    private class AesPort(keyBytes: ByteArray) : CipherPort {
        private val key = SecretKeySpec(keyBytes, "AES")
        override fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
            return c.iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }
    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val codec = SecretCodec(AesPort(key()))

    @Test fun roundTripsAndDoesNotStorePlaintext() {
        val enc = codec.encrypt("tmdb-key-123")!!
        assertTrue(enc.startsWith("v1:"))
        assertFalse(enc.contains("tmdb"))
        assertEquals("tmdb-key-123", codec.decrypt(enc))
        assertNotEquals(enc, codec.encrypt("tmdb-key-123")) // fresh IV each time
    }

    @Test fun tamperedWrongKeyOrMalformedIsNull() {
        val enc = codec.encrypt("secret")!!
        val flipped = enc.dropLast(1) + (if (enc.last() == '0') '1' else '0')
        assertNull(codec.decrypt(flipped))
        assertNull(SecretCodec(AesPort(key())).decrypt(enc))
        assertNull(codec.decrypt("not encrypted"))
        assertNull(codec.decrypt("v1:zz"))
        assertNull(codec.decrypt("v1:abc"))
    }

    @Test fun throwingPortNeverEscapes() {
        val bad = SecretCodec(object : CipherPort {
            override fun seal(plain: ByteArray): ByteArray = error("keystore gone")
            override fun open(sealed: ByteArray): ByteArray = error("keystore gone")
        })
        assertNull(bad.encrypt("x"))
        assertNull(bad.decrypt("v1:00"))
    }

    @Test fun apiKeyStorageRules() {
        val enc = codec.encrypt("abc")!!
        assertEquals("abc", ApiKeyStorage.read(enc, null, codec))
        assertEquals("abc", ApiKeyStorage.read(null, "abc", codec)) // legacy plain value until migrated
        assertEquals("", ApiKeyStorage.read(enc, "stale-plain", SecretCodec(AesPort(key())))) // undecryptable: unset, no plain fallback
        assertEquals("", ApiKeyStorage.read(null, null, codec))
        assertNull(ApiKeyStorage.sealForWrite("  ", codec))
        assertNull(ApiKeyStorage.sealForWrite("k", SecretCodec(object : CipherPort {
            override fun seal(plain: ByteArray): ByteArray = error("x")
            override fun open(sealed: ByteArray): ByteArray = error("x")
        })))
    }

    @Test fun backgroundAudioDecisions() {
        assertTrue(BackgroundAudio.keepPlayingOnStop(isPlaying = true, finishing = false))
        assertFalse(BackgroundAudio.keepPlayingOnStop(isPlaying = false, finishing = false))
        assertFalse(BackgroundAudio.keepPlayingOnStop(isPlaying = true, finishing = true))
        assertTrue(BackgroundAudio.shouldRequestNotificationPermission(33, granted = false, askedBefore = false))
        assertFalse(BackgroundAudio.shouldRequestNotificationPermission(33, granted = false, askedBefore = true))
        assertFalse(BackgroundAudio.shouldRequestNotificationPermission(33, granted = true, askedBefore = false))
        assertFalse(BackgroundAudio.shouldRequestNotificationPermission(32, granted = false, askedBefore = false))
    }
}
