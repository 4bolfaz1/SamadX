package ir.abdev.samadx.ui

import android.app.Activity
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.abdev.samadx.Graph
import ir.abdev.samadx.R
import ir.abdev.samadx.databinding.DialogPrivacyBinding

/** متن کامل حریم خصوصی و شرایط استفاده. */
object PrivacyDialog {

    private fun content(activity: Activity): DialogPrivacyBinding {
        val d = DialogPrivacyBinding.inflate(activity.layoutInflater)
        d.text.text = HtmlCompat.fromHtml(activity.getString(R.string.privacy_full), HtmlCompat.FROM_HTML_MODE_COMPACT)
        return d
    }

    /** فقط خواندن (از صفحه‌ی ورود یا حساب). */
    fun showFull(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.privacy_title)
            .setView(content(activity).root)
            .setPositiveButton(R.string.privacy_understood, null)
            .showRtl()
    }

    /**
     * برای کاربرانی که پیش از اضافه شدن این متن وارد شده‌اند (یا وقتی متن مهمی عوض شود):
     * تا نپذیرند برنامه ادامه نمی‌دهد؛ «نمی‌پذیرم» برنامه را می‌بندد.
     */
    fun requireAcceptance(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.privacy_title)
            .setView(content(activity).root)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_accept) { _, _ -> Graph.accounts.acceptPrivacy() }
            .setNegativeButton(R.string.privacy_decline) { _, _ -> activity.finishAffinity() }
            .showRtl()
    }
}
