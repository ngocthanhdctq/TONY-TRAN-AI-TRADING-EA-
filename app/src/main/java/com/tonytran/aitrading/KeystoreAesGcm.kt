package com.tonytran.aitrading

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-local authenticated encryption backed by Android Keystore.
 * The AES key is non-exportable; only ciphertext and IV are stored in preferences.
 */
class KeystoreAesGcm(private val alias: String) {
    private val transformation = "AES/GCM/NoPadding"
    private val keyStoreType = "AndroidKeyStore"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12 + 16) { "Ciphertext không hợp lệ." }
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
