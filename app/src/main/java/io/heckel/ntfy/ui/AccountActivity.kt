package io.heckel.ntfy.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.AccountApi
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the ntfy account of a configured user: usage and limits, reserved topics, access tokens,
 * and lets the user change the password. Data is only loaded while this screen is open.
 */
class AccountActivity : AppCompatActivity() {
    private val repository by lazy { Repository.getInstance(this) }
    private val api by lazy { AccountApi(this) }
    private var user: User? = null

    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var errorView: TextView
    private lateinit var sections: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        val toolbarLayout = findViewById<View>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(Colors.statusBarNormal(this, dynamicColors, darkMode))
        val toolbar = toolbarLayout.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.account_title)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        refresh = findViewById(R.id.account_refresh)
        errorView = findViewById(R.id.account_error)
        sections = findViewById(R.id.account_sections)
        refresh.setOnRefreshListener { load() }
        findViewById<View>(R.id.account_change_password).setOnClickListener { showChangePasswordDialog() }
        findViewById<View>(R.id.account_reservation_add).setOnClickListener { showAddReservationDialog() }

        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        refresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            val currentUser = if (baseUrl != null) repository.getUser(baseUrl) else repository.getUsers().firstOrNull()
            user = currentUser
            val result = try {
                if (currentUser == null) {
                    Result.failure(Exception(getString(R.string.account_error_no_user)))
                } else {
                    Result.success(api.get(currentUser))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to load account: ${e.message}")
                Result.failure(e)
            }
            withContext(Dispatchers.Main) {
                refresh.isRefreshing = false
                result.onSuccess { render(currentUser!!, it) }
                result.onFailure { showError(it) }
            }
        }
    }

    private fun showError(e: Throwable) {
        sections.isVisible = false
        errorView.isVisible = true
        errorView.text = errorMessage(e)
    }

    private fun errorMessage(e: Throwable): String {
        return if (e is ApiService.UnauthorizedException) {
            getString(R.string.account_error_unauthorized)
        } else {
            getString(R.string.account_error, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun render(user: User, account: AccountApi.Account) {
        errorView.isVisible = false
        sections.isVisible = true

        findViewById<TextView>(R.id.account_username).text = account.username ?: user.username
        val details = listOfNotNull(
            if (account.role == AccountApi.ROLE_ADMIN) getString(R.string.account_role_admin) else null,
            account.tier?.name,
            shortUrl(user.baseUrl)
        )
        findViewById<TextView>(R.id.account_details).text = details.joinToString(" · ")

        renderUsage(account)
        renderReservations(user, account)
        renderTokens(user, account)
    }

    private fun renderUsage(account: AccountApi.Account) {
        val container = findViewById<LinearLayout>(R.id.account_usage)
        container.removeAllViews()
        val limits = account.limits
        val stats = account.stats
        // Admins have no limits; only show rows that actually have a limit, so the card stays short
        if (account.role != AccountApi.ROLE_ADMIN && limits != null && stats != null) {
            addUsageRow(container, R.string.account_usage_messages, stats.messages, limits.messages, ::formatCount)
            addUsageRow(container, R.string.account_usage_emails, stats.emails, limits.emails, ::formatCount)
            addUsageRow(container, R.string.account_usage_calls, stats.calls, limits.calls, ::formatCount)
            addUsageRow(container, R.string.account_usage_reservations, stats.reservations, limits.reservations, ::formatCount)
            addUsageRow(container, R.string.account_usage_attachments, stats.attachmentTotalSize, limits.attachmentTotalSize) {
                Formatter.formatShortFileSize(this, it)
            }
        }
        val hasRows = container.childCount > 0
        findViewById<View>(R.id.account_usage_title).isVisible = hasRows
        findViewById<MaterialCardView>(R.id.account_usage_card).isVisible = hasRows
    }

    private fun addUsageRow(container: LinearLayout, labelResId: Int, used: Long?, limit: Long?, format: (Long) -> String) {
        if (limit == null || limit <= 0) return
        val usedValue = used ?: 0
        val row = LayoutInflater.from(this).inflate(R.layout.item_account_usage, container, false)
        row.findViewById<TextView>(R.id.account_usage_label).text = getString(labelResId)
        row.findViewById<TextView>(R.id.account_usage_value).text =
            getString(R.string.account_usage_value, format(usedValue), format(limit))
        row.findViewById<LinearProgressIndicator>(R.id.account_usage_progress).apply {
            max = 1000
            progress = ((usedValue.toDouble() / limit) * 1000).toInt().coerceIn(0, 1000)
        }
        container.addView(row)
    }

    private fun formatCount(value: Long) = "%,d".format(value)

    private fun renderReservations(user: User, account: AccountApi.Account) {
        val reservations = account.reservations.orEmpty()
        val canReserve = account.role == AccountApi.ROLE_ADMIN || (account.limits?.reservations ?: 0) > 0
        findViewById<View>(R.id.account_reservations_header).isVisible = canReserve || reservations.isNotEmpty()
        findViewById<View>(R.id.account_reservation_add).isVisible = canReserve
        findViewById<View>(R.id.account_reservations_card).isVisible = reservations.isNotEmpty()

        val container = findViewById<LinearLayout>(R.id.account_reservations)
        container.removeAllViews()
        reservations.sortedBy { it.topic }.forEach { reservation ->
            addRow(container, reservation.topic, everyoneLabel(reservation.everyone)) {
                confirmAndRun(getString(R.string.account_reservation_delete_confirm, reservation.topic)) {
                    api.deleteReservation(user, reservation.topic)
                }
            }
        }
    }

    private fun renderTokens(user: User, account: AccountApi.Account) {
        val container = findViewById<LinearLayout>(R.id.account_tokens)
        container.removeAllViews()
        account.tokens.orEmpty().forEach { token ->
            val isThisDevice = token.token == user.password
            val title = token.label?.ifEmpty { null } ?: maskToken(token.token)
            val subtitle = if (isThisDevice) {
                getString(R.string.account_token_this_device)
            } else {
                getString(R.string.account_token_last_access, relativeTime(token.lastAccess))
            }
            // The token of this app can't be deleted here, that would log the app out
            val onDelete: (() -> Unit)? = if (isThisDevice || token.provisioned == true) null else {
                {
                    confirmAndRun(getString(R.string.account_token_delete_confirm, title)) {
                        api.deleteToken(user, token.token)
                    }
                }
            }
            addRow(container, title, subtitle, onDelete)
        }
    }

    private fun addRow(container: LinearLayout, title: String, subtitle: String, onDelete: (() -> Unit)?) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_account_row, container, false)
        row.findViewById<TextView>(R.id.account_row_title).text = title
        row.findViewById<TextView>(R.id.account_row_subtitle).text = subtitle
        val deleteButton = row.findViewById<ImageButton>(R.id.account_row_delete)
        deleteButton.isVisible = onDelete != null
        deleteButton.setOnClickListener { onDelete?.invoke() }
        container.addView(row)
    }

    private fun confirmAndRun(message: String, action: suspend () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.account_delete) { _, _ -> runAndReload(action) }
            .setNegativeButton(R.string.account_cancel, null)
            .show()
    }

    private fun runAndReload(action: suspend () -> Unit, successMessage: String? = null) {
        refresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val error = try {
                action()
                null
            } catch (e: Exception) {
                Log.w(TAG, "Account action failed: ${e.message}")
                e
            }
            withContext(Dispatchers.Main) {
                val message = if (error != null) errorMessage(error) else successMessage
                message?.let { Toast.makeText(this@AccountActivity, it, Toast.LENGTH_LONG).show() }
                load()
            }
        }
    }

    private fun showAddReservationDialog() {
        val user = user ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_account_reservation, null)
        val topicView = view.findViewById<TextInputEditText>(R.id.account_reservation_topic)
        val everyoneView = view.findViewById<RadioGroup>(R.id.account_reservation_everyone)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_reservation_add_title)
            .setView(view)
            .setPositiveButton(R.string.account_reservation_add) { _, _ ->
                val topic = topicView.text?.toString()?.trim().orEmpty()
                if (topic.isEmpty()) return@setPositiveButton
                val everyone = when (everyoneView.checkedRadioButtonId) {
                    R.id.account_reservation_read_only -> AccountApi.EVERYONE_READ_ONLY
                    R.id.account_reservation_write_only -> AccountApi.EVERYONE_WRITE_ONLY
                    R.id.account_reservation_read_write -> AccountApi.EVERYONE_READ_WRITE
                    else -> AccountApi.EVERYONE_DENY_ALL
                }
                runAndReload({ api.addReservation(user, topic, everyone) })
            }
            .setNegativeButton(R.string.account_cancel, null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val user = user ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_account_password, null)
        val currentView = view.findViewById<TextInputEditText>(R.id.account_password_current)
        val newView = view.findViewById<TextInputEditText>(R.id.account_password_new)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_change_password)
            .setView(view)
            .setPositiveButton(R.string.account_password_save) { _, _ ->
                val current = currentView.text?.toString().orEmpty()
                val new = newView.text?.toString().orEmpty()
                if (current.isEmpty() || new.isEmpty()) return@setPositiveButton
                // The app keeps using its token, so nothing has to be stored locally
                runAndReload({ api.changePassword(user, current, new) }, getString(R.string.account_password_changed))
            }
            .setNegativeButton(R.string.account_cancel, null)
            .show()
    }

    private fun everyoneLabel(everyone: String?): String {
        return when (everyone) {
            AccountApi.EVERYONE_READ_ONLY -> getString(R.string.account_everyone_read_only)
            AccountApi.EVERYONE_WRITE_ONLY -> getString(R.string.account_everyone_write_only)
            AccountApi.EVERYONE_READ_WRITE -> getString(R.string.account_everyone_read_write)
            else -> getString(R.string.account_everyone_deny_all)
        }
    }

    private fun relativeTime(unixSeconds: Long?): String {
        if (unixSeconds == null || unixSeconds <= 0) return getString(R.string.account_token_never_used)
        // Lowercase, since it continues a sentence ("Zuletzt genutzt vor 2 Stunden", not "... Vor 2 Stunden")
        return DateUtils.getRelativeTimeSpanString(unixSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
            .replaceFirstChar { it.lowercase() }
    }

    private fun maskToken(token: String) = if (token.length > 10) token.take(6) + "…" + token.takeLast(4) else token

    companion object {
        private const val TAG = "NtfyAccountActivity"
        const val EXTRA_BASE_URL = "baseUrl"

        fun start(context: Context, baseUrl: String? = null) {
            val intent = Intent(context, AccountActivity::class.java)
            baseUrl?.let { intent.putExtra(EXTRA_BASE_URL, it) }
            context.startActivity(intent)
        }
    }
}
