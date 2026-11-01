package io.heckel.ntfy.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.util.Pair
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.ui.InboxFilter.Companion.NO_TIME
import io.heckel.ntfy.util.AfterChangedTextWatcher
import java.util.Calendar
import java.util.TimeZone

/** Filter for [InboxFragment]. Every change applies right away and is remembered. */
class InboxFilterFragment : BottomSheetDialogFragment() {
    private val repository by lazy { (requireActivity().application as Application).repository }
    private val inbox get() = parentFragment as InboxFragment
    private val filter get() = inbox.filter
    private lateinit var root: View
    private lateinit var textField: TextInputEditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_inbox_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        textField = view.findViewById(R.id.inbox_filter_text)
        textField.setText(filter.text)
        textField.addTextChangedListener(AfterChangedTextWatcher {
            val text = textField.text.toString()
            if (text != filter.text) apply(filter.copy(text = text), updateText = false)
        })
        view.findViewById<View>(R.id.inbox_filter_reset).setOnClickListener { apply(InboxFilter()) }
        view.findViewById<View>(R.id.inbox_filter_save).setOnClickListener { saveView() }
        render()
    }

    private fun apply(newFilter: InboxFilter, updateText: Boolean = true) {
        inbox.setFilter(newFilter)
        if (updateText && textField.text.toString() != newFilter.text) textField.setText(newFilter.text)
        render()
    }

    private fun render() {
        root.findViewById<TextView>(R.id.inbox_filter_title).text = getString(R.string.inbox_filter_title, inbox.countFor(filter))
        renderViews()
        renderStatus()
        renderPeriod()
        renderTime()
        renderPriorities()
        renderTopics()
        renderMore()
    }

    private fun group(id: Int) = root.findViewById<ChipGroup>(id).apply { removeAllViews() }

    private fun ChipGroup.chip(label: String, checked: Boolean, onClick: () -> Unit): Chip {
        val chip = Chip(context).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener { onClick() }
        }
        addView(chip)
        return chip
    }

    private fun renderViews() {
        val group = group(R.id.inbox_filter_views)
        val presets = listOf(
            R.string.inbox_view_unread to InboxFilter(status = InboxFilter.STATUS_UNREAD),
            R.string.inbox_view_important to InboxFilter(priorities = setOf(4, 5)),
            R.string.inbox_view_today to InboxFilter(period = InboxFilter.PERIOD_TODAY),
            R.string.inbox_view_night to InboxFilter(timeFrom = 22 * 60, timeTo = 6 * 60)
        )
        presets.forEach { (label, preset) ->
            group.chip(getString(label), filter == preset) { apply(if (filter == preset) InboxFilter() else preset) }
        }
        InboxFilter.loadViews(repository).forEach { view ->
            val chip = group.chip(view.name, filter == view.filter) { apply(if (filter == view.filter) InboxFilter() else view.filter) }
            chip.setOnLongClickListener { deleteView(view); true }
        }
    }

    private fun renderStatus() {
        val group = group(R.id.inbox_filter_status)
        listOf(
            InboxFilter.STATUS_ALL to R.string.inbox_status_all,
            InboxFilter.STATUS_UNREAD to R.string.inbox_status_unread,
            InboxFilter.STATUS_READ to R.string.inbox_status_read
        ).forEach { (status, label) ->
            group.chip(getString(label), filter.status == status) { apply(filter.copy(status = status)) }
        }
    }

    private fun renderPeriod() {
        val group = group(R.id.inbox_filter_period)
        listOf(
            InboxFilter.PERIOD_ALL to R.string.inbox_period_all,
            InboxFilter.PERIOD_TODAY to R.string.inbox_period_today,
            InboxFilter.PERIOD_24H to R.string.inbox_period_24h,
            InboxFilter.PERIOD_7D to R.string.inbox_period_7d,
            InboxFilter.PERIOD_30D to R.string.inbox_period_30d
        ).forEach { (period, label) ->
            group.chip(getString(label), filter.period == period) { apply(filter.copy(period = period)) }
        }
        val custom = filter.period == InboxFilter.PERIOD_CUSTOM
        group.chip(if (custom) filter.customPeriodLabel() else getString(R.string.inbox_period_custom), custom) { pickPeriod() }
    }

    private fun renderTime() {
        val group = group(R.id.inbox_filter_time)
        val fromLabel = if (filter.timeFrom == NO_TIME) getString(R.string.inbox_time_from_empty) else getString(R.string.inbox_time_from, InboxFilter.formatTime(filter.timeFrom))
        val toLabel = if (filter.timeTo == NO_TIME) getString(R.string.inbox_time_to_empty) else getString(R.string.inbox_time_to, InboxFilter.formatTime(filter.timeTo))
        group.timeChip(fromLabel, filter.timeFrom, { apply(filter.copy(timeFrom = it)) })
        group.timeChip(toLabel, filter.timeTo, { apply(filter.copy(timeTo = it)) })
    }

    private fun ChipGroup.timeChip(label: String, minutes: Int, onChange: (Int) -> Unit) {
        val set = minutes != NO_TIME
        val chip = chip(label, set) { pickTime(if (set) minutes else 0, onChange) }
        chip.isCloseIconVisible = set
        chip.setOnCloseIconClickListener { onChange(NO_TIME) }
    }

    private fun renderPriorities() {
        val group = group(R.id.inbox_filter_priority)
        (1..5).forEach { priority ->
            group.chip(priority.toString(), priority in filter.priorities) {
                val priorities = if (priority in filter.priorities) filter.priorities - priority else filter.priorities + priority
                apply(filter.copy(priorities = priorities))
            }
        }
    }

    private fun renderTopics() {
        val group = group(R.id.inbox_filter_topics)
        inbox.topics().forEach { topic ->
            val excluded = topic.id in filter.excludedTopics
            group.chip(inbox.topicName(topic), !excluded) {
                val topics = if (excluded) filter.excludedTopics - topic.id else filter.excludedTopics + topic.id
                apply(filter.copy(excludedTopics = topics))
            }
        }
    }

    private fun renderMore() {
        val group = group(R.id.inbox_filter_more)
        group.chip(getString(R.string.inbox_more_attachment), filter.withAttachment) { apply(filter.copy(withAttachment = !filter.withAttachment)) }
        group.chip(getString(R.string.inbox_more_tags), filter.withTags) { apply(filter.copy(withTags = !filter.withTags)) }
        group.chip(getString(R.string.inbox_more_actions), filter.withActions) { apply(filter.copy(withActions = !filter.withActions)) }
    }

    private fun pickPeriod() {
        val builder = MaterialDatePicker.Builder.dateRangePicker().setTitleText(R.string.inbox_filter_section_period)
        if (filter.period == InboxFilter.PERIOD_CUSTOM) {
            builder.setSelection(Pair(localToUtcMillis(filter.from), localToUtcMillis(filter.to)))
        }
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            val from = utcToLocalSeconds(selection.first, startOfDay = true)
            val to = utcToLocalSeconds(selection.second, startOfDay = false)
            apply(filter.copy(period = InboxFilter.PERIOD_CUSTOM, from = from, to = to))
        }
        picker.show(childFragmentManager, "period")
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(minutes / 60)
            .setMinute(minutes % 60)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(childFragmentManager, "time")
    }

    /** The date picker works in UTC midnights, the filter in local time. */
    private fun utcToLocalSeconds(utcMillis: Long, startOfDay: Boolean): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        val local = Calendar.getInstance()
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), if (startOfDay) 0 else 23, if (startOfDay) 0 else 59, if (startOfDay) 0 else 59)
        return local.timeInMillis / 1000
    }

    private fun localToUtcMillis(seconds: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = seconds * 1000 }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        return utc.timeInMillis
    }

    private fun saveView() {
        val input = EditText(requireContext()).apply { setSingleLine() }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.inbox_filter_save_view_title)
            .setView(container)
            .setPositiveButton(R.string.inbox_filter_save_view_button) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val views = InboxFilter.loadViews(repository).filterNot { it.name == name } + InboxView(name, filter)
                    InboxFilter.saveViews(repository, views)
                    render()
                }
            }
            .setNegativeButton(R.string.inbox_filter_cancel, null)
            .show()
    }

    private fun deleteView(view: InboxView) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.inbox_filter_delete_view, view.name))
            .setPositiveButton(R.string.inbox_filter_delete_button) { _, _ ->
                InboxFilter.saveViews(repository, InboxFilter.loadViews(repository) - view)
                render()
            }
            .setNegativeButton(R.string.inbox_filter_cancel, null)
            .show()
    }

    companion object {
        const val TAG = "NtfyInboxFilterFragment"
    }
}
