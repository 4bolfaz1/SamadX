package ir.abdev.samadx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ir.abdev.samadx.R
import ir.abdev.samadx.data.Credit
import ir.abdev.samadx.databinding.ItemChangeBinding
import ir.abdev.samadx.databinding.ItemCreditSummaryBinding
import ir.abdev.samadx.databinding.ItemSectionTitleBinding
import ir.abdev.samadx.util.Fa
import com.google.android.material.color.MaterialColors

/** ردیف ۰: خلاصه‌ی موجودی کیف پول، ردیف ۱: عنوان بخش، بقیه: شارژهای قبلی. */
class CreditAdapter(
    private val onIncrease: () -> Unit,
    private val onTransfer: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var credit: Credit? = null

    /**
     * با اعلان‌های جزئی (payload) همان ردیف‌ها به‌روز می‌شوند تا ViewHolder خلاصه حفظ شود و
     * نوار «آخرین به‌روزرسانی» بتواند نرم ظاهر/محو شود (notifyDataSetChanged همه را از نو می‌ساخت).
     */
    fun submit(credit: Credit) {
        val old = this.credit
        this.credit = credit
        if (old == null) {
            notifyDataSetChanged()
            return
        }
        val oldCount = 2 + old.changes.size
        val newCount = 2 + credit.changes.size
        notifyItemRangeChanged(0, minOf(oldCount, newCount), PAYLOAD_UPDATE)
        if (newCount > oldCount) notifyItemRangeInserted(oldCount, newCount - oldCount)
        else if (newCount < oldCount) notifyItemRangeRemoved(newCount, oldCount - newCount)
    }

    override fun getItemCount(): Int = credit?.let { 2 + it.changes.size } ?: 0

    override fun getItemViewType(position: Int): Int = when (position) {
        0 -> TYPE_SUMMARY
        1 -> TYPE_TITLE
        else -> TYPE_CHANGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUMMARY -> SummaryVH(ItemCreditSummaryBinding.inflate(inflater, parent, false))
            TYPE_TITLE -> TitleVH(ItemSectionTitleBinding.inflate(inflater, parent, false))
            else -> ChangeVH(ItemChangeBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val c = credit ?: return
        when (holder) {
            is SummaryVH -> bindSummary(holder, c)
            is TitleVH -> holder.b.title.setText(
                if (c.changes.isEmpty()) R.string.credit_empty else R.string.credit_history
            )
            is ChangeVH -> {
                val change = c.changes[position - 2]
                val ctx = holder.b.root.context
                holder.b.date.text = Fa.dateTime(change.date)
                holder.b.amount.text = "+ " + Fa.money(change.amount)
                holder.b.receipt.isVisible = !change.receipt.isNullOrBlank()
                holder.b.receipt.text = ctx.getString(R.string.credit_receipt) + ": " + Fa.digits(change.receipt.orEmpty())
            }
        }
    }

    private fun bindSummary(vh: SummaryVH, c: Credit) {
        val b = vh.b
        val negative = c.credit < 0
        val card = b.root
        val bg = if (negative) com.google.android.material.R.attr.colorErrorContainer
        else com.google.android.material.R.attr.colorPrimaryContainer
        val fg = if (negative) com.google.android.material.R.attr.colorOnErrorContainer
        else com.google.android.material.R.attr.colorOnPrimaryContainer
        val fgColor = MaterialColors.getColor(card, fg)

        card.setCardBackgroundColor(MaterialColors.getColor(card, bg))
        b.label.setText(if (negative) R.string.credit_label_negative else R.string.credit_label_positive)
        b.amount.text = Fa.money(c.credit) // علامت منفی نمی‌گذاریم؛ برچسب «بدهکاری» معنی را می‌رساند
        b.subsidy.isVisible = c.subsidy != 0L
        b.subsidy.text = card.context.getString(R.string.credit_subsidy) + ": " + Fa.money(c.subsidy)
        val at = c.cachedAt
        val ctx = card.context
        b.stale.setTextColor(fgColor)
        vh.notice.update(
            when {
                at == null -> null
                c.cacheError == null -> ctx.getString(R.string.cache_notice_plain, Fa.lastUpdate(at))
                else -> ctx.getString(R.string.cache_notice_error, c.cacheError, Fa.lastUpdate(at))
            }
        )
        b.btnIncrease.setOnClickListener { onIncrease() }
        b.btnTransfer.setOnClickListener { onTransfer() }
        b.label.setTextColor(fgColor)
        b.amount.setTextColor(fgColor)
        b.subsidy.setTextColor(fgColor)
    }

    private class SummaryVH(val b: ItemCreditSummaryBinding) : RecyclerView.ViewHolder(b.root) {
        val notice = NoticeBar(b.stale)
    }
    private class TitleVH(val b: ItemSectionTitleBinding) : RecyclerView.ViewHolder(b.root)
    private class ChangeVH(val b: ItemChangeBinding) : RecyclerView.ViewHolder(b.root)

    private companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_TITLE = 1
        const val TYPE_CHANGE = 2
        const val PAYLOAD_UPDATE = "update"
    }
}
