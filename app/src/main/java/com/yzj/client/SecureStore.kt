package com.yzj.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore backed AES-GCM storage for tokens and API keys.
 * Ciphertext is stored in app-private SharedPreferences; the key never leaves
 * the Keystore.
 */
object SecureStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "yzj_vault_v1"
    private const val PREFS = "yzj_secure"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun put(context: Context, name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            remove(context, name)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val packed = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
            val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
            val dir = File(context.filesDir, "secure").apply { mkdirs() }
            val target = file(context, name)
            val tmp = File(target.absolutePath + ".tmp")
            tmp.writeText(encoded)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(encoded)
                tmp.delete()
            }
        }
    }

    fun get(context: Context, name: String): String? {
        val encoded = readEncoded(context, name) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(context: Context, name: String) {
        file(context, name).delete()
        prefs(context).edit().remove(name).apply()
    }

    fun has(context: Context, name: String): Boolean =
        file(context, name).isFile || prefs(context).contains(name)

    fun putJson(context: Context, name: String, json: JSONObject) {
        put(context, name, json.toString())
    }

    fun getJson(context: Context, name: String): JSONObject? {
        val text = get(context, name) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun sessionName(slot: Int) = "session:$slot"
    fun slotApiKeyName(slot: Int) = "apiKey:slot:$slot"
    fun profileApiKeyName(profile: String) = "apiKey:profile:$profile"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context, name: String): File {
        val safe = Base64.encodeToString(
            name.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return File(File(context.filesDir, "secure"), safe)
    }

    private fun readEncoded(context: Context, name: String): String? {
        val f = file(context, name)
        if (f.isFile) return runCatching { f.readText() }.getOrNull()
        // Migrate values written by the previous SharedPreferences version.
        // MODE_MULTI_PROCESS makes the legacy read refresh across processes.
        val legacy = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
                .getString(name, null)
        }.getOrNull() ?: return null
        runCatching {
            val plain = decryptEncoded(legacy)
            if (plain != null) put(context, name, plain)
        }
        return legacy
    }

    private fun decryptEncoded(encoded: String): String? = runCatching {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
