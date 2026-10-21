package ir.abdev.samadx.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import ir.abdev.samadx.data.ActivityItem
import ir.abdev.samadx.databinding.ItemActivityBinding
import ir.abdev.samadx.util.Fa

/** فهرست گزارش عملکرد (جدیدترین بالا؛ ترتیب همان است که سرور می‌دهد). */
class ActivityAdapter : RecyclerView.Adapter<ActivityAdapter.VH>() {

    private var items: List<ActivityItem> = emptyList()

    fun submit(list: List<ActivityItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        val b = h.b
        b.title.text = it.title
        b.detail.isVisible = it.detail.isNotBlank()
        b.detail.text = it.detail
        b.extra.isVisible = !it.extra.isNullOrBlank()
        b.extra.text = it.extra
        b.date.text = Fa.dateTime(it.occur)

        val amount = it.amountRial
        b.amount.isVisible = amount != null && amount != 0L
        if (amount != null && amount != 0L) {
            val negative = amount < 0
            b.amount.text = (if (negative) "− " else "+ ") + Fa.money(kotlin.math.abs(amount))
            val attr = if (negative) androidx.appcompat.R.attr.colorError
            else androidx.appcompat.R.attr.colorPrimary
            b.amount.setTextColor(MaterialColors.getColor(b.root, attr))
        }
    }

    class VH(val b: ItemActivityBinding) : RecyclerView.ViewHolder(b.root)
}
