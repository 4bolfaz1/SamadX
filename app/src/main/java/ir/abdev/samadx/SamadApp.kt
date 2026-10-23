package ir.abdev.samadx

import android.app.Application
import com.google.android.material.color.DynamicColors

class SamadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ir.abdev.samadx.util.Net.init(this)
        Graph.init(this)
        // رنگ‌های پویای Material 3 (اندروید ۱۲ به بالا)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // زبان فارسی/راست‌به‌چپ در BaseActivity اعمال می‌شود و در Manifest (localeConfig) به سیستم اعلام شده است.
    }
}
