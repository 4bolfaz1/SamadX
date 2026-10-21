package ir.abdev.samadx.data

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
 * ذخیره‌ی رمزشده‌ی مقدارهای حساس (رمز عبور و توکن‌ها) بدون هیچ کتابخانه‌ی اضافه.
 * کلید AES-256 داخل Android Keystore ساخته می‌شود و از دستگاه خارج نمی‌شود؛
 * مقدارها با AES/GCM رمز و در SharedPreferences نگه داشته می‌شوند.
 */
class SecureStore(context: Context, name: String = "samad_secure") {

    // هر حساب فایل جدای خودش را دارد؛ کلید Keystore برای همه یکی است.
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, b64(iv) + ":" + b64(data)).apply()
        } catch (e: Exception) {
            // اگر Keystore در دسترس نبود چیزی ذخیره نمی‌شود؛ کاربر باید دوباره وارد شود.
            prefs.edit().remove(name).apply()
        }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val parts = stored.split(":")
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
            String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "samad_client_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
