package ir.abdev.samadx

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * پایه‌ی همه‌ی صفحه‌ها: زبان برنامه همیشه فارسی (ایران) و راست‌به‌چپ است،
 * حتی اگر زبان گوشی انگلیسی باشد. دیالوگ‌ها و Snackbar هم از همین Context استفاده می‌کنند.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapPersian(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Locale.setDefault(PERSIAN)
        super.onCreate(savedInstanceState)
    }

    private companion object {
        val PERSIAN: Locale = Locale.forLanguageTag("fa-IR")

        fun wrapPersian(base: Context): Context {
            Locale.setDefault(PERSIAN)
            val config = Configuration(base.resources.configuration)
            config.setLocale(PERSIAN)
            config.setLayoutDirection(PERSIAN)
            return base.createConfigurationContext(config)
        }
    }
}
