package nl.jake.volume_remote

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
 * Device identity for native standby polling. The secret is encrypted with an
 * AES-GCM key kept in Android Keystore; only the non-secret UUID is stored plain.
 */
internal object CloudCredentialStore {
    private const val PREFS = "volume_remote_cloud"
    private const val KEY_ALIAS = "volume_remote_cloud_secret_v1"
    private const val DEVICE_ID = "device_id"
    private const val SECRET_CIPHER = "secret_cipher"
    private const val SECRET_IV = "secret_iv"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(context: Context, deviceId: String, deviceSecret: String) {
        require(deviceId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(deviceSecret.length >= 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(deviceSecret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICE_ID, deviceId)
            .putString(SECRET_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(SECRET_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun read(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(DEVICE_ID, null) ?: return null
        val encrypted = prefs.getString(SECRET_CIPHER, null) ?: return null
        val iv = prefs.getString(SECRET_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val secret = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            if (secret.length < 32) null else Pair(deviceId, secret)
        } catch (_: Exception) {
            null
        }
    }
}