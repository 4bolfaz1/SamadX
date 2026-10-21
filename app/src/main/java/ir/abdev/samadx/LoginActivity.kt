package ir.abdev.samadx

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.abdev.samadx.data.Accounts
import ir.abdev.samadx.data.ApiException
import ir.abdev.samadx.databinding.ActivityLoginBinding
import ir.abdev.samadx.ui.PrivacyDialog
import ir.abdev.samadx.ui.applySystemBarsPadding
import ir.abdev.samadx.ui.showRtl
import ir.abdev.samadx.util.Fa
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * ورود. سه حالت:
 * - عادی: اگر حساب فعال با اطلاعات معتبر داریم مستقیم به برنامه می‌رویم (ورود خودکار).
 * - افزودن حساب (EXTRA_ADD): فرم ورود برای یک حساب دیگر؛ «بازگشت» به حساب فعلی.
 * - ورود دوباره (EXTRA_USERNAME): رمز این حساب نامعتبر شده؛ نام کاربری پر شده و کش‌های حساب می‌مانند.
 * پیش از اولین ورود، کاربر باید متن حریم خصوصی را بپذیرد.
 */
class LoginActivity : BaseActivity() {

    private lateinit var b: ActivityLoginBinding
    private var addMode = false
    private var loading = false
    private var needConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addMode = intent.getBooleanExtra(EXTRA_ADD, false)
        // ورود خودکار: توکن را لایه‌ی شبکه در اولین درخواست می‌گیرد/تمدید می‌کند.
        if (!addMode && Graph.hasSession()) {
            openMain()
            return
        }

        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()

        val prefill = intent.getStringExtra(EXTRA_USERNAME)
        if (!prefill.isNullOrBlank()) b.usernameInput.setText(prefill)
        intent.getStringExtra(EXTRA_MESSAGE)?.let { showError(it) }

        // ---- تأیید حریم خصوصی (فقط تا وقتی یک‌بار پذیرفته نشده) ----
        needConsent = !Graph.accounts.privacyAccepted
        b.consentGroup.isVisible = needConsent
        b.consentCheck.setOnCheckedChangeListener { _, _ -> updateButton() }
        b.btnReadPrivacy.setOnClickListener { PrivacyDialog.showFull(this) }

        // ---- حالت‌های افزودن / ورود دوباره ----
        b.btnBack.isVisible = addMode
        b.btnBack.setOnClickListener { finish() }
        b.btnRemoveAccount.isVisible = !addMode && !prefill.isNullOrBlank()
        b.btnRemoveAccount.setOnClickListener { confirmRemove(prefill.orEmpty()) }
        if (addMode) b.loginSubtitle.setText(R.string.login_subtitle_add)

        b.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        b.loginButton.setOnClickListener { submit() }
        updateButton()
    }

    private fun updateButton() {
        b.loginButton.isEnabled = !loading && (!needConsent || b.consentCheck.isChecked)
    }

    private fun submit() {
        if (loading) return
        if (needConsent && !b.consentCheck.isChecked) {
            showError(getString(R.string.login_need_consent))
            return
        }
        val username = Fa.toLatinDigits(b.usernameInput.text?.toString().orEmpty().trim())
        val password = b.passwordInput.text?.toString().orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_fill_all))
            return
        }

        if (needConsent) Graph.accounts.acceptPrivacy()
        setLoading(true)
        b.errorText.isVisible = false
        lifecycleScope.launch {
            try {
                Graph.login(username, password)
                openMain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                showError(e.userMessage)
            } catch (e: Exception) {
                showError(getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun confirmRemove(username: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout_yes) { _, _ ->
                val id = Accounts.normalize(username)
                val remaining = Graph.removeAccount(id)
                if (remaining) {
                    openMain()
                } else {
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
            }
            .showRtl()
    }

    private fun setLoading(on: Boolean) {
        loading = on
        b.progress.isVisible = on
        b.loginButton.text = if (on) "" else getString(R.string.login_button)
        b.usernameInput.isEnabled = !on
        b.passwordInput.isEnabled = !on
        updateButton()
    }

    private fun showError(message: String) {
        b.errorText.text = message
        b.errorText.isVisible = true
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ADD = "add_account"
        const val EXTRA_USERNAME = "username"

        /**
         * نشست حساب فعال نامعتبر شده: رمز و توکن آن پاک می‌شود (حساب و کش‌هایش می‌مانند)
         * و صفحه‌ی ورود با نام کاربری پر‌شده باز می‌شود.
         */
        fun reloginIntent(ctx: Context, message: String?): Intent {
            val username = Graph.store.username()
            Graph.repo.invalidateSession()
            return Intent(ctx, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_USERNAME, username)
        }
    }
}
