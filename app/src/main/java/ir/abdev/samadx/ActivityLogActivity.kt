package ir.abdev.samadx

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ir.abdev.samadx.data.ApiException
import ir.abdev.samadx.data.AuthRequiredException
import ir.abdev.samadx.databinding.ActivityLogBinding
import ir.abdev.samadx.ui.ActivityAdapter
import ir.abdev.samadx.ui.applySystemBarsPadding
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** گزارش عملکرد: رزروها، لغوها، دریافت غذا و تراکنش‌های اعتبار در ۳۰ روز اخیر. */
class ActivityLogActivity : BaseActivity() {

    private lateinit var b: ActivityLogBinding
    private val adapter = ActivityAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()

        b.toolbar.subtitle = getString(R.string.activity_hint)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener {
            b.swipe.isRefreshing = false // نشانگر پیشرفت همان نوار بالای صفحه است
            load(force = true)
        }
        load(force = false)
    }

    /** اول نسخه‌ی کش‌شده؛ فقط اگر اقدامی رخ داده یا کاربر تازه‌سازی کند از سرور (با نوار پیشرفت کوچک بالا). */
    private fun load(force: Boolean) {
        b.message.isVisible = false
        val cached = Graph.repo.cachedActivityLog()
        if (cached != null) show(cached)
        lifecycleScope.launch {
            b.topProgress.visibility = android.view.View.VISIBLE
            try {
                show(Graph.repo.activityLog(force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                startActivity(LoginActivity.reloginIntent(this@ActivityLogActivity, e.message))
            } catch (e: Exception) {
                val text = if (e is ApiException) e.userMessage else getString(R.string.error_unexpected, e.message ?: "")
                if (cached != null) {
                    com.google.android.material.snackbar.Snackbar.make(b.root, text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                } else {
                    b.message.text = text + "\n\n" + getString(R.string.pull_to_retry)
                    b.message.isVisible = true
                }
            } finally {
                b.topProgress.visibility = android.view.View.INVISIBLE
            }
        }
    }

    private fun show(items: List<ir.abdev.samadx.data.ActivityItem>) {
        adapter.submit(items)
        b.message.isVisible = items.isEmpty()
        if (items.isEmpty()) b.message.text = getString(R.string.activity_empty)
    }
}
