package ir.abdev.samadx.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.abdev.samadx.Graph
import ir.abdev.samadx.LoginActivity
import ir.abdev.samadx.MainActivity
import ir.abdev.samadx.R
import ir.abdev.samadx.data.AccountInfo
import ir.abdev.samadx.data.Accounts
import ir.abdev.samadx.databinding.ItemAccountBinding
import ir.abdev.samadx.databinding.SheetAccountsBinding
import ir.abdev.samadx.util.Fa

/**
 * برگه‌ی حساب‌ها: جابه‌جایی بین حساب‌ها بدون خروج، افزودن حساب (تا Accounts.MAX) و خروج از یک حساب.
 * با جابه‌جایی، MainActivity از نو ساخته می‌شود؛ همه‌ی صفحه‌ها اول کش حساب تازه را نشان می‌دهند.
 */
object AccountSwitcher {

    fun show(activity: Activity) {
        val dialog = BottomSheetDialog(activity)
        val v = SheetAccountsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(v.root)
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        render(activity, dialog, v)
        dialog.show()
    }

    private fun render(activity: Activity, dialog: BottomSheetDialog, v: SheetAccountsBinding) {
        val accounts = Graph.accounts
        val active = accounts.active()?.id
        v.count.text = activity.getString(R.string.accounts_count, Fa.count(accounts.count()), Fa.count(Accounts.MAX))
        v.list.removeAllViews()

        for (info in accounts.all()) {
            val row = ItemAccountBinding.inflate(activity.layoutInflater, v.list, true)
            val label = Graph.labelOf(info)
            val isActive = info.id == active
            row.avatar.text = label.firstOrNull()?.toString() ?: "?"
            row.name.text = label
            row.sid.text = activity.getString(R.string.account_student_id, Fa.digits(info.id))
            row.sid.isVisible = label != info.id
            row.activeIcon.isVisible = isActive
            row.card.setCardBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    row.card,
                    if (isActive) com.google.android.material.R.attr.colorPrimaryContainer
                    else com.google.android.material.R.attr.colorSurfaceContainerHighest
                )
            )
            row.card.setOnClickListener {
                if (!isActive) {
                    dialog.dismiss()
                    Graph.switchTo(info.id)
                    restartMain(activity)
                }
            }
            row.btnRemove.setOnClickListener { confirmRemove(activity, dialog, v, info, isActive) }
        }

        v.btnAdd.isEnabled = accounts.canAdd()
        v.note.setText(if (accounts.canAdd()) R.string.accounts_note else R.string.accounts_full)
        v.btnAdd.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ADD, true))
        }
    }

    private fun confirmRemove(activity: Activity, dialog: BottomSheetDialog, v: SheetAccountsBinding, info: AccountInfo, wasActive: Boolean) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(activity.getString(R.string.logout_account_message, Graph.labelOf(info)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout_yes) { _, _ ->
                val remaining = Graph.removeAccount(info.id)
                when {
                    !remaining -> {
                        dialog.dismiss()
                        activity.startActivity(
                            Intent(activity, LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                    wasActive -> {
                        dialog.dismiss()
                        restartMain(activity)
                    }
                    else -> render(activity, dialog, v)
                }
            }
            .showRtl()
    }

    /** برنامه را روی حساب فعال از نو باز می‌کند (با محو‌شدگی نرم). */
    @Suppress("DEPRECATION")
    fun restartMain(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
