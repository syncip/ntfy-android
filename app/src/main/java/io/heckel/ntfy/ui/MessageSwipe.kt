package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification

/**
 * Swipe gestures of all message lists: to the right toggles read/unread, to the left deletes.
 * The toggle keeps the item in place, [onToggleRead] only changes its state.
 */
class MessageSwipe(
    context: Context,
    private val notificationAt: (Int) -> Notification,
    private val onToggleRead: (Notification) -> Unit,
    private val onDelete: (Notification) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val readColor = Colors.primary(context)
    private val onReadColor = Colors.onPrimary(context)
    private val deleteColor = Colors.dangerText(context)
    private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_done_all_white_24dp)!!.mutate()
    private val unreadIcon = ContextCompat.getDrawable(context, R.drawable.ic_mark_email_unread_white_24dp)!!.mutate()
    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete_white_20dp)!!.mutate()
    private val paint = Paint()
    private val iconMargin = (24 * context.resources.displayMetrics.density).toInt()
    private val helper = ItemTouchHelper(this)
    private var recyclerView: RecyclerView? = null

    fun attachTo(list: RecyclerView) {
        recyclerView = list
        helper.attachToRecyclerView(list)
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
        val position = viewHolder.absoluteAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val notification = notificationAt(position)
        if (swipeDir == ItemTouchHelper.RIGHT) {
            helper.attachToRecyclerView(null) // Stops holding the item at its swiped-out position
            helper.attachToRecyclerView(recyclerView)
            viewHolder.bindingAdapter?.notifyItemChanged(position)
            onToggleRead(notification)
        } else {
            onDelete(notification)
        }
    }

    override fun onChildDraw(canvas: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
        val item = viewHolder.itemView
        val position = viewHolder.absoluteAdapterPosition
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f && position != RecyclerView.NO_POSITION) {
            val right = dX > 0
            paint.color = if (right) readColor else deleteColor
            val left = if (right) item.left.toFloat() else item.right + dX
            val end = if (right) item.left + dX else item.right.toFloat()
            canvas.save()
            canvas.clipRect(left, item.top.toFloat(), end, item.bottom.toFloat())
            canvas.drawRect(left, item.top.toFloat(), end, item.bottom.toFloat(), paint)
            val icon = when {
                !right -> deleteIcon
                notificationAt(position).notificationId != 0 -> readIcon
                else -> unreadIcon
            }
            icon.setTint(onReadColor)
            val top = item.top + (item.height - icon.intrinsicHeight) / 2
            val x = if (right) item.left + iconMargin else item.right - iconMargin - icon.intrinsicWidth
            icon.setBounds(x, top, x + icon.intrinsicWidth, top + icon.intrinsicHeight)
            icon.draw(canvas)
            canvas.restore()
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
