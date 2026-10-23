package ir.abdev.samadx.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * بررسی سریع و بدون ترافیک وضعیت اینترنت گوشی.
 * عمداً NET_CAPABILITY_VALIDATED بررسی نمی‌شود: روی بعضی شبکه‌های داخلی تست اتصال گوگل شکست می‌خورد
 * در حالی که سرور دانشگاه در دسترس است.
 */
object Net {
    private var cm: ConnectivityManager? = null

    fun init(context: Context) {
        cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /** اگر مطمئن نباشیم (مثلاً دسترسی نداریم) آنلاین فرض می‌شود تا کاربر بی‌دلیل بلاک نشود. */
    fun isOnline(): Boolean {
        val m = cm ?: return true
        return try {
            val n = m.activeNetwork ?: return false
            m.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } catch (e: SecurityException) {
            true
        }
    }
}
