package ir.abdev.samadx.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ir.abdev.samadx.Graph
import ir.abdev.samadx.LoginActivity
import ir.abdev.samadx.R
import ir.abdev.samadx.data.Self
import ir.abdev.samadx.ActivityLogActivity
import ir.abdev.samadx.databinding.DialogPasswordBinding
import ir.abdev.samadx.databinding.FragmentAccountBinding
import ir.abdev.samadx.util.AppLinks
import ir.abdev.samadx.util.Fa
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** مشخصات کاربر، انتخاب سلف و خروج از حساب. */
class AccountFragment : Fragment() {

    private var _b: FragmentAccountBinding? = null
    private val b get() = _b!!
    private var selfs: List<Self> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAccountBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // نام از پاسخ ورود می‌آید؛ هیچ درخواستی برای مشخصات کاربر لازم نیست.
        val username = Graph.store.username().orEmpty()
        val fullName = Graph.store.fullName
        b.userName.text = if (fullName.isNullOrBlank()) username else fullName
        // شماره دانشجویی = نام کاربری هنگام ورود (اگر نام نداریم، نام کاربری خودش عنوان است و تکرار نمی‌شود)
        val studentId = Fa.toLatinDigits(username).trim()
        b.studentId.isVisible = !fullName.isNullOrBlank() && studentId.isNotEmpty()
        b.studentId.text = getString(R.string.account_student_id, Fa.digits(studentId))
        b.selfName.text = Graph.store.selfName ?: "—"
        b.btnLogout.setOnClickListener { confirmLogout() }
        b.btnAccounts.setOnClickListener { AccountSwitcher.show(requireActivity()) }
        b.accountsCount.text = getString(R.string.accounts_count, Fa.count(Graph.accounts.count()), Fa.count(ir.abdev.samadx.data.Accounts.MAX))
        b.btnPrivacy.setOnClickListener { PrivacyDialog.showFull(requireActivity()) }
        b.btnChangeSelf.setOnClickListener { chooseSelf() }
        b.btnActivity.setOnClickListener { startActivity(Intent(requireContext(), ActivityLogActivity::class.java)) }
        b.btnPassword.setOnClickListener { showPasswordDialog() }
        b.btnTelegram.setOnClickListener {
            confirmOpenLink(R.string.open_telegram_title, R.string.open_telegram_message, AppLinks.TELEGRAM)
        }
        b.btnGithub.setOnClickListener {
            confirmOpenLink(R.string.open_github_title, R.string.open_github_message, AppLinks.GITHUB)
        }
        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull()
        b.appVersion.isVisible = version != null
        if (version != null) b.appVersion.text = getString(R.string.app_version, Fa.digits(version))
        loadSelfs()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    /** سلف‌ها فقط یک بار از اینترنت گرفته و تا خروج از حساب نگه داشته می‌شوند. */
    private fun loadSelfs() {
        Graph.repo.cachedSelfs()?.let { showSelfs(it); return }
        viewLifecycleOwner.lifecycleScope.launch {
            safely(onError = {}) { Graph.repo.selfs() }?.let { if (_b != null) showSelfs(it) }
        }
    }

    private fun showSelfs(list: List<Self>) {
        selfs = list
        b.btnChangeSelf.isVisible = list.size > 1
        if (Graph.store.selfName.isNullOrBlank()) {
            list.firstOrNull { it.id == Graph.store.selfId }?.let {
                Graph.store.selfName = it.name
                b.selfName.text = it.name
            }
        }
    }

    /** پیش از باز کردن هر لینک بیرونی از کاربر تأیید می‌گیرد. */
    private fun confirmOpenLink(titleRes: Int, messageRes: Int, url: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_telegram_yes) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: android.content.ActivityNotFoundException) {
                    snack(getString(R.string.open_telegram_failed))
                }
            }
            .showRtl()
    }

    private fun showPasswordDialog() {
        val d = DialogPasswordBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_change_password)
            .setView(d.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pw_save, null) // در setOnShowListener تا خطا دیالوگ را نبندد
            .create()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setOnClickListener {
                d.currentLayout.error = null
                d.newLayout.error = null
                d.repeatLayout.error = null
                val current = d.currentInput.text?.toString().orEmpty()
                val new = d.newInput.text?.toString().orEmpty()
                val repeat = d.repeatInput.text?.toString().orEmpty()
                when {
                    current.isEmpty() -> d.currentLayout.error = getString(R.string.pw_required)
                    // مقایسه‌ی محلی با رمز ذخیره‌شده: رمز غلط اصلاً به سرور نمی‌رود
                    current != Graph.store.password() -> d.currentLayout.error = getString(R.string.pw_wrong_current)
                    new.isEmpty() -> d.newLayout.error = getString(R.string.pw_required)
                    new == current -> d.newLayout.error = getString(R.string.pw_same)
                    repeat != new -> d.repeatLayout.error = getString(R.string.pw_mismatch)
                    else -> {
                        button.isEnabled = false
                        viewLifecycleOwner.lifecycleScope.launch {
                            val ok = safely(onError = { d.newLayout.error = it }) {
                                Graph.repo.changePassword(current, new)
                                true
                            }
                            if (ok == true) {
                                dialog.dismiss()
                                if (_b != null) snack(getString(R.string.pw_done))
                            } else {
                                button.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun chooseSelf() {
        val names = selfs.map { it.name }.toTypedArray()
        val checked = selfs.indexOfFirst { it.id == Graph.store.selfId }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_self)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val chosen = selfs[which]
                Graph.repo.selectSelf(chosen)
                b.selfName.text = chosen.name
                dialog.dismiss()
                snack(getString(R.string.account_self_changed))
            }
            .setNegativeButton(R.string.cancel, null)
            .showRtl()
    }

    /** خروج فقط از همین حساب؛ بقیه‌ی حساب‌ها می‌مانند و یکی‌شان فعال می‌شود. */
    private fun confirmLogout() {
        val info = Graph.accounts.active() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_confirm_title)
            .setMessage(getString(R.string.logout_account_message, Graph.labelOf(info)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout_yes) { _, _ ->
                val remaining = Graph.removeAccount(info.id)
                if (remaining) {
                    AccountSwitcher.restartMain(requireActivity())
                } else {
                    startActivity(
                        Intent(requireContext(), LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
            }
            .showRtl()
    }
}
