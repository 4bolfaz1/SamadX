package ir.abdev.samadx.ui

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.abdev.samadx.Graph
import ir.abdev.samadx.R
import ir.abdev.samadx.databinding.DialogTransferBinding
import ir.abdev.samadx.databinding.FragmentCreditBinding
import ir.abdev.samadx.util.Fa
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** اعتبار فعلی، افزایش اعتبار (درگاه)، انتقال اعتبار و تاریخچه‌ی شارژها. */
class CreditFragment : Fragment() {

    private var _b: FragmentCreditBinding? = null
    private val b get() = _b!!
    private val adapter = CreditAdapter(
        onIncrease = { startCharge() },
        onTransfer = { showTransferDialog() }
    )
    private var chargeJob: Job? = null
    private var transferJob: Job? = null
    private var refreshOnResume = false
    private var paymentJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentCreditBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.swipe.setOnRefreshListener {
            b.swipe.isRefreshing = false // نشانگر پیشرفت همان نوار بالای صفحه است
            load(force = true)
        }
        if (Graph.store.paymentBase != null) {
            // از صفحه‌ی پرداخت برگشته‌ایم (مثلاً با deep link): اعتبار تازه + اعلام نتیجه
            Graph.repo.cachedCredit()?.let { adapter.submit(it) }
            checkPayment()
        } else {
            load(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // بازگشت از مرورگر (با deep link یا دستی)
        if (refreshOnResume && _b != null) {
            refreshOnResume = false
            checkPayment()
        }
    }

    override fun onDestroyView() {
        setLoading(false)
        _b = null
        super.onDestroyView()
    }

    /** برای deep link: بازگشت از پرداخت. */
    fun onReturnedFromPayment() {
        if (_b != null) checkPayment()
    }

    /**
     * اعتبار: اول مقدار ذخیره‌شده؛ فقط اگر هنوز نداریم یا force باشد از سرور (با نوار پیشرفت کوچک بالا).
     */
    private fun load(force: Boolean) {
        val cached = Graph.repo.cachedCredit()
        if (cached != null) {
            b.error.isVisible = false
            adapter.submit(cached)
        }
        if (!force && cached != null) return
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val credit = safely(onError = { if (Graph.repo.cachedCredit() == null) showError(it) else snack(it) }) {
                Graph.repo.credit(force)
            }
            setLoading(false)
            if (credit != null) {
                b.error.isVisible = false
                adapter.submit(credit)
            }
        }
    }

    /**
     * پس از بازگشت از پرداخت: اعتبار از سرور گرفته می‌شود و اگر بیشتر شده بود، مبلغ افزایش
     * به کاربر اعلام و تأیید گرفته می‌شود.
     */
    private fun checkPayment() {
        if (paymentJob?.isActive == true) return
        val base = Graph.store.paymentBase
        Graph.store.paymentBase = null
        setLoading(true)
        paymentJob = viewLifecycleOwner.lifecycleScope.launch {
            val credit = safely(onError = { if (Graph.repo.cachedCredit() == null) showError(it) else snack(it) }) {
                Graph.repo.credit(force = true)
            }
            setLoading(false)
            if (credit == null) {
                Graph.store.paymentBase = base // بعداً دوباره بررسی شود
                return@launch
            }
            b.error.isVisible = false
            adapter.submit(credit)
            if (credit.cacheError != null) {
                Graph.store.paymentBase = base // سرور جواب نداد؛ مقدار قدیمی است
                return@launch
            }
            val diff = if (base != null) credit.credit - base else 0L
            if (diff > 0) {
                Graph.repo.markLogDirty()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.charge_ok_title)
                    .setMessage(getString(R.string.charge_ok_message, Fa.money(diff), Fa.money(credit.credit)))
                    .setPositiveButton(R.string.ok, null)
                    .showRtl()
            } else if (base != null) {
                snack(getString(R.string.charge_no_change))
            }
        }
    }

    // ------------------------------------------------------------ افزایش اعتبار

    /** یک درخواست (توکن ورود) و سپس باز شدن صفحه‌ی شارژ در مرورگر گوشی. */
    private fun startCharge() {
        if (chargeJob?.isActive == true) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.charge_confirm_title)
            .setMessage(R.string.charge_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.charge_open_browser) { _, _ -> openChargePage() }
            .showRtl()
    }

    private fun openChargePage() {
        if (chargeJob?.isActive == true) return
        chargeJob = viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            val url = safely { Graph.repo.walletChargeUrl() }
            setLoading(false)
            if (url == null) return@launch
            try {
                refreshOnResume = true
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                refreshOnResume = false
                Graph.store.paymentBase = null
                snack(getString(R.string.charge_failed))
            }
        }
    }

    // ------------------------------------------------------------ انتقال اعتبار

    private fun showTransferDialog() {
        if (transferJob?.isActive == true) return
        val d = DialogTransferBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.credit_transfer)
            .setView(d.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.transfer_continue, null) // در setOnShowListener تا خطا دیالوگ را نبندد
            .create()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                d.usernameLayout.error = null
                d.amountLayout.error = null
                val username = Fa.toLatinDigits(d.usernameInput.text?.toString().orEmpty()).trim()
                val amountText = Fa.toLatinDigits(d.amountInput.text?.toString().orEmpty()).trim()
                val toman = amountText.toLongOrNull()

                when {
                    username.isEmpty() ->
                        d.usernameLayout.error = getString(R.string.transfer_username_required)
                    username == Graph.store.username() ->
                        d.usernameLayout.error = getString(R.string.transfer_self)
                    toman == null || toman <= 0 || toman > MAX_TOMAN || !amountText.all { it.isDigit() } ->
                        d.amountLayout.error = getString(R.string.transfer_amount_invalid)
                    else -> {
                        dialog.dismiss()
                        confirmTransfer(username, toman)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmTransfer(username: String, toman: Long) {
        val rial = toman * 10
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.transfer_confirm_title)
            .setMessage(getString(R.string.transfer_confirm_message, Fa.money(rial), Fa.digits(username)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.transfer_do) { _, _ -> doTransfer(username, rial) }
            .showRtl()
    }

    private fun doTransfer(username: String, rial: Long) {
        if (transferJob?.isActive == true) return
        transferJob = viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            val result = safely { Graph.repo.transferCredit(username, rial) }
            setLoading(false)
            if (result != null) {
                // مبلغ از اعتبار ذخیره‌شده کم شده است (آفلاین)؛ فقط نمایش را تازه می‌کنیم
                Graph.repo.cachedCredit()?.let { adapter.submit(it) }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.transfer_done_title)
                    .setMessage(getString(R.string.transfer_done_message, Fa.money(result.amountRial), result.recipient))
                    .setPositiveButton(R.string.close, null)
                    .showRtl()
            }
        }
    }

    private fun showError(message: String) {
        b.error.text = message + "\n\n" + getString(R.string.pull_to_retry)
        b.error.isVisible = true
    }

    private companion object {
        /** سقف منطقی ورودی (تومان) برای جلوگیری از اشتباه تایپی. */
        const val MAX_TOMAN = 100_000_000L
    }
}
