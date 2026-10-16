package cn.market.assistant.data.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider API keys encrypted with an Android Keystore key. The key never enters the ledger
 * database or the backup payload; clearing it only removes the local ciphertext.
 */
class LlmSecretStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveApiKey(providerKey: String, apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        preferences.edit().putString(preferenceKey(providerKey), payload).apply()
    }

    fun loadApiKey(providerKey: String): String? = runCatching {
        val payload = preferences.getString(preferenceKey(providerKey), null) ?: return null
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun hasApiKey(providerKey: String): Boolean = loadApiKey(providerKey) != null

    fun clearApiKey(providerKey: String) {
        preferences.edit().remove(preferenceKey(providerKey)).apply()
    }

    private fun preferenceKey(providerKey: String): String = "key_${providerKey.lowercase()}"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "assistant_llm_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "assistant_llm_key_wrapper_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val AAD = "market-assistant/llm-key/v1".toByteArray(Charsets.UTF_8)
    }
}
