package com.ming.focusplan.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_model_keys", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun save(alias: String, value: String) {
        if (value.isBlank()) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        preferences.edit().putString(alias, payload).apply()
    }

    fun read(alias: String): String? {
        val encoded = preferences.getString(alias, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_SIZE)
            val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(MASTER_ALIAS, null) as SecretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private const val MASTER_ALIAS = "focus_plan_model_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
