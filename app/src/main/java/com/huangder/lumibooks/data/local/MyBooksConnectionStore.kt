package com.huangder.lumibooks.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account passwords are never persisted. The device token is encrypted with Android Keystore. */
class MyBooksConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("mybooks_secure", Context.MODE_PRIVATE)
    val serverUrl: String? get() = preferences.getString("server_url", null)
    val username: String get() = preferences.getString("username", "").orEmpty()
    fun token(): String = runCatching {
        val iv = preferences.getString("iv", null) ?: return ""
        val value = preferences.getString("token", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrDefault("")

    fun save(serverUrl: String, username: String, token: String) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("server_url", serverUrl).putString("username", username)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(value, Base64.NO_WRAP)).commit()) {
            "无法保存设备登录凭据"
        }
    }
    fun clearToken() {
        check(preferences.edit().remove("iv").remove("token").commit()) { "无法清除设备登录凭据" }
    }
    fun clear() {
        check(preferences.edit().clear().commit()) { "无法清除服务器配置" }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private companion object { const val KEY_ALIAS = "mybooks_device_session" }
}
