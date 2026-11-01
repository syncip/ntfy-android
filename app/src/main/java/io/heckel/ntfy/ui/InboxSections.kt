package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Section titles above the messages: "NEW" for everything unread, then one title per day.
 * Drawn as decoration, so the list adapter stays the same as on the topic page.
 */
class InboxSections(private val context: Context, private val items: () -> List<Notification>) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val height = (34 * density).toInt()
    private val startPadding = 16 * density
    private val newColor = Colors.primary(context)
    private val dayColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position != RecyclerView.NO_POSITION && title(position) != null) outRect.top = height
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val title = title(position) ?: continue
            paint.color = if (isUnread(position)) newColor else dayColor
            canvas.drawText(title, startPadding, child.top + child.translationY - 10 * density, paint)
        }
    }

    private fun isUnread(position: Int) = items().getOrNull(position)?.notificationId != 0

    private fun title(position: Int): String? {
        val list = items()
        val notification = list.getOrNull(position) ?: return null
        val previous = list.getOrNull(position - 1)
        if (notification.notificationId != 0) {
            return if (position == 0) context.getString(R.string.inbox_section_new, list.count { it.notificationId != 0 }) else null
        }
        val day = dayKey(notification.timestamp)
        if (previous != null && previous.notificationId == 0 && dayKey(previous.timestamp) == day) return null
        return dayTitle(notification.timestamp)
    }

    private fun dayTitle(timestamp: Long): String {
        val today = dayKey(System.currentTimeMillis() / 1000)
        val yesterday = dayKey(System.currentTimeMillis() / 1000 - 24 * 60 * 60)
        return when (dayKey(timestamp)) {
            today -> context.getString(R.string.inbox_section_today)
            yesterday -> context.getString(R.string.inbox_section_yesterday)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp * 1000))
        }
    }

    private fun dayKey(timestamp: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp * 1000 }
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
}
