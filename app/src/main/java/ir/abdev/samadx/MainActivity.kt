package ir.abdev.samadx

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ir.abdev.samadx.databinding.ActivityMainBinding
import ir.abdev.samadx.ui.AccountFragment
import ir.abdev.samadx.ui.AccountSwitcher
import ir.abdev.samadx.ui.PrivacyDialog
import ir.abdev.samadx.ui.CreditFragment
import ir.abdev.samadx.ui.QuickFragment
import ir.abdev.samadx.ui.WeekFragment
import ir.abdev.samadx.ui.applySystemBarsPadding

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private val loadingOwners = HashSet<Any>()

    /** نوار پیشرفت بالای صفحه؛ تا وقتی حداقل یک صفحه منتظر پاسخ سرور است دیده می‌شود. */
    fun setLoading(owner: Any, on: Boolean) {
        if (on) loadingOwners.add(owner) else loadingOwners.remove(owner)
        if (::b.isInitialized) {
            b.topProgress.visibility = if (loadingOwners.isEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Graph.hasSession()) {
            // حساب فعال نداریم یا رمزش نامعتبر شده: ورود (با نام کاربری پر‌شده در صورت وجود حساب)
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_USERNAME, Graph.accounts.active()?.let { Graph.storeOf(it).username() })
            )
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()

        // حساب فعال زیر عنوان؛ دکمه‌ی بالا برگه‌ی جابه‌جایی/افزودن حساب را باز می‌کند
        b.toolbar.inflateMenu(R.menu.main_toolbar)
        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_accounts) {
                AccountSwitcher.show(this)
                true
            } else {
                false
            }
        }
        b.toolbar.subtitle = Graph.accounts.active()?.let { Graph.labelOf(it) }

        if (!Graph.accounts.privacyAccepted) PrivacyDialog.requireAcceptance(this)

        b.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_quick -> QuickFragment()
                R.id.nav_week -> WeekFragment()
                R.id.nav_credit -> CreditFragment()
                else -> AccountFragment()
            }
            b.toolbar.title = item.title
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.frag_enter, R.anim.frag_exit)
                .replace(R.id.container, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            b.bottomNav.selectedItemId = R.id.nav_quick
            handleDeepLink(intent)
        } else {
            b.toolbar.title = b.bottomNav.menu.findItem(b.bottomNav.selectedItemId)?.title
        }
    }

    /**
     * هر بار که برنامه جلو می‌آید (و حداکثر روزی یک بار موفق): هفته‌ی تازه‌تمام‌شده را از سرور می‌گیرد
     * تا نسخه‌ی نهایی در آرشیو بماند. خطاها بی‌صدا نادیده گرفته می‌شوند و بار بعد دوباره تلاش می‌شود.
     */
    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized) return
        lifecycleScope.launch {
            try {
                Graph.repo.archivePastWeeks()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // بی‌صدا
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::b.isInitialized) handleDeepLink(intent)
    }

    /** samadx://wallet ← بازگشت از صفحه‌ی پرداخت: تب اعتبار با اعتبار تازه. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "samadx" || data.host != "wallet") return
        intent.data = null // با چرخش صفحه دوباره پردازش نشود
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if (current is CreditFragment) current.onReturnedFromPayment()
        else b.bottomNav.selectedItemId = R.id.nav_credit
    }
}
