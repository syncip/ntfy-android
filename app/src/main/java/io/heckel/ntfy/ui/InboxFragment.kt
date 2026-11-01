package io.heckel.ntfy.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.MessageActions
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "All messages": every message of all topics, unread ones first, with a filter that is remembered. */
class InboxFragment : Fragment(R.layout.fragment_inbox) {
    private val repository by lazy { (requireActivity().application as Application).repository }
    private lateinit var adapter: DetailAdapter
    private lateinit var list: RecyclerView
    private lateinit var listContainer: SwipeRefreshLayout
    private lateinit var filterBar: View
    private lateinit var filterBarText: TextView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyReset: View

    private var allNotifications: List<Notification>? = null
    private var subscriptions: Map<Long, Subscription>? = null
    var filter = InboxFilter()
        private set
    var shown: List<Notification> = emptyList()
        private set
    val shownUnread get() = shown.filter { it.notificationId != 0 }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        filter = InboxFilter.load(repository)
        list = view.findViewById(R.id.inbox_list)
        listContainer = view.findViewById(R.id.inbox_list_container)
        filterBar = view.findViewById(R.id.inbox_filter_bar)
        filterBarText = view.findViewById(R.id.inbox_filter_bar_text)
        empty = view.findViewById(R.id.inbox_empty)
        emptyText = view.findViewById(R.id.inbox_empty_text)
        emptyReset = view.findViewById(R.id.inbox_empty_reset)

        adapter = DetailAdapter(
            requireActivity(), lifecycleScope, repository,
            { onClick(it) }, { onLongClick(it) },
            topicName = { topicName(it) }, onTopicClick = { openTopic(it) }
        )
        list.adapter = adapter
        list.addItemDecoration(InboxSections(requireContext()) { shown })
        MessageSwipe(requireContext(), { adapter.get(it) }, { toggleRead(it) }, { delete(it) }).attachTo(list)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val atTop = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() <= itemCount
                if (positionStart == 0 && atTop) list.scrollToPosition(0)
            }
        })

        listContainer.setColorSchemeColors(Colors.swipeToRefreshColor(requireContext()))
        listContainer.setOnRefreshListener { (activity as? MainActivity)?.refreshAllSubscriptions() }
        view.findViewById<View>(R.id.inbox_filter_bar_clear).setOnClickListener { setFilter(InboxFilter()) }
        filterBarText.setOnClickListener { openFilter() }
        emptyReset.setOnClickListener { setFilter(InboxFilter()) }

        repository.getAllNotificationsLiveData().observe(viewLifecycleOwner) {
            allNotifications = it
            update()
        }
        repository.getSubscriptionsLiveData().observe(viewLifecycleOwner) { list ->
            subscriptions = list.associateBy { it.id }
            update()
        }
    }

    override fun onResume() {
        super.onResume()
        update() // Periods like "today" depend on the current time
    }

    fun openFilter() {
        InboxFilterFragment().show(childFragmentManager, InboxFilterFragment.TAG)
    }

    fun setRefreshing(refreshing: Boolean) {
        if (this::listContainer.isInitialized) listContainer.isRefreshing = refreshing
    }

    fun setFilter(newFilter: InboxFilter) {
        filter = newFilter
        InboxFilter.save(repository, newFilter)
        update()
    }

    fun topics(): List<Subscription> = subscriptions?.values?.sortedBy { displayName(appBaseUrl(), it) }.orEmpty()

    fun topicName(subscription: Subscription) = displayName(appBaseUrl(), subscription)

    fun countFor(candidate: InboxFilter): Int = visible().count { candidate.matches(it) }

    /** The check mark button: what is shown right now, and nothing that the filter hides. */
    fun markShownAsRead() {
        val messages = shownUnread
        if (messages.isEmpty()) return
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) { MessageActions.markRead(context, messages) }
        Snackbar.make(requireView(), getString(R.string.inbox_marked_read, messages.size), Snackbar.LENGTH_LONG)
            .setAction(R.string.detail_item_snack_undo) {
                lifecycleScope.launch(Dispatchers.IO) { MessageActions.markUnread(context, messages) }
            }
            .show()
    }

    private fun visible(): List<Notification> {
        val topics = subscriptions ?: return emptyList()
        return allNotifications.orEmpty().filter { it.subscriptionId in topics }
    }

    private fun update() {
        if (!isAdded || allNotifications == null || subscriptions == null) return
        val now = System.currentTimeMillis() / 1000
        val (unread, read) = visible().filter { filter.matches(it, now) }.partition { it.notificationId != 0 }
        val newList = unread + read
        shown = newList
        adapter.submitList(newList) { list.invalidateItemDecorations() }
        filterBar.isVisible = filter.isActive
        filterBarText.text = filter.summary(requireContext())
        listContainer.isVisible = newList.isNotEmpty()
        empty.isVisible = newList.isEmpty()
        emptyReset.isVisible = filter.isActive
        emptyText.setText(if (filter.isActive) R.string.inbox_empty_filtered else R.string.inbox_empty)
        (activity as? MainActivity)?.onInboxChanged()
    }

    private fun topicName(notification: Notification): String? {
        return subscriptions?.get(notification.subscriptionId)?.let { topicName(it) }
    }

    private fun appBaseUrl() = getString(R.string.app_base_url)

    private fun onClick(notification: Notification) {
        if (notification.notificationId != 0) markRead(notification)
        if (notification.click != "") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, notification.click.toUri()))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open click URL", e)
                Toast.makeText(context, getString(R.string.detail_item_cannot_open_url, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onLongClick(notification: Notification) {
        val unread = notification.notificationId != 0
        val items = arrayOf(
            getString(if (unread) R.string.inbox_mark_read else R.string.inbox_mark_unread),
            getString(R.string.inbox_copy),
            getString(R.string.inbox_open_topic),
            getString(R.string.inbox_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleRead(notification)
                    1 -> copyToClipboard(requireContext(), "notification", decodeMessage(notification))
                    2 -> openTopic(notification)
                    3 -> delete(notification)
                }
            }
            .show()
    }

    private fun openTopic(notification: Notification) {
        subscriptions?.get(notification.subscriptionId)?.let { (activity as? MainActivity)?.openTopic(it) }
    }

    private fun markRead(notification: Notification) {
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) { MessageActions.markRead(context, listOf(notification)) }
    }

    private fun toggleRead(notification: Notification) {
        if (notification.notificationId != 0) {
            markRead(notification)
        } else {
            val context = requireContext().applicationContext
            lifecycleScope.launch(Dispatchers.IO) { MessageActions.markUnread(context, listOf(notification)) }
        }
    }

    private fun delete(notification: Notification) {
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            NotificationService(context).cancel(notification)
            repository.markAsDeleted(notification.id)
        }
        Snackbar.make(requireView(), R.string.detail_item_snack_deleted, Snackbar.LENGTH_SHORT)
            .setAction(R.string.detail_item_snack_undo) {
                lifecycleScope.launch(Dispatchers.IO) { repository.undeleteNotification(notification.id) }
            }
            .show()
    }

    companion object {
        const val TAG = "NtfyInboxFragment"
    }
}
