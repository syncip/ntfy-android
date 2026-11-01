package io.heckel.ntfy.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.AccountSyncer
import io.heckel.ntfy.msg.MarkAllRead
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.ConnectionCheck
import io.heckel.ntfy.msg.DownloadManager
import io.heckel.ntfy.msg.DownloadType
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.msg.ReadSync
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.SUBSCRIPTION_ICONS
import io.heckel.ntfy.util.dangerButton
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.isIgnoringBatteryOptimizations
import io.heckel.ntfy.util.isNetworkAvailable
import io.heckel.ntfy.util.maybeSplitTopicUrl
import io.heckel.ntfy.util.randomSubscriptionId
import io.heckel.ntfy.util.shortUrl
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.DeleteWorker
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import androidx.core.content.FileProvider
import androidx.core.view.size
import androidx.core.view.get
import androidx.core.net.toUri

class MainActivity : AppCompatActivity(), AddFragment.SubscribeListener, NotificationFragment.NotificationSettingsListener {
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api by lazy { ApiService(this) }
    private val poller by lazy { Poller(api, repository) }
    private val messenger = FirebaseMessenger()

    // UI elements
    private lateinit var menu: Menu
    private var hasUsers = false
    private lateinit var mainList: RecyclerView
    private lateinit var mainListContainer: SwipeRefreshLayout
    private lateinit var adapter: MainAdapter
    private lateinit var fab: FloatingActionButton
    private lateinit var tabs: TabLayout
    private lateinit var inboxContainer: View
    private lateinit var noEntries: View
    private var subscriptions: List<Subscription> = emptyList()
    private val inbox get() = supportFragmentManager.findFragmentById(R.id.main_inbox_fragment) as? InboxFragment
    private val inboxShown get() = tabs.selectedTabPosition == TAB_MESSAGES

    // Other stuff
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var workManager: WorkManager? = null // Context-dependent
    private var dispatcher: NotificationDispatcher? = null // Context-dependent
    private var appBaseUrl: String? = null // Context-dependent

    // Action mode stuff
    private var actionMode: ActionMode? = null
    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            actionMode = mode
            if (mode != null) {
                mode.menuInflater.inflate(R.menu.menu_main_action_mode, menu)
                mode.title = "1" // One item selected
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.main_action_mode_delete -> {
                    onMultiDeleteClick()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            endActionModeAndRedraw()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.init(this) // Init logs in all entry points
        repository.setAppQuit(false) // Opening the app brings everything back after "Quit"
        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        workManager = WorkManager.getInstance(this)
        dispatcher = NotificationDispatcher(this, repository)
        appBaseUrl = getString(R.string.app_base_url)

        // Action bar
        val toolbarLayout = findViewById<AppBarLayout>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val statusBarColor = Colors.statusBarNormal(this, dynamicColors, darkMode)
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(statusBarColor)
        
        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        toolbar.overflowIcon?.setTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        title = getString(R.string.app_name)
        
        // Set system status bar appearance
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        // Floating action button (check mark): Applies to what is shown, see updateFab()
        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            if (inboxShown) inbox?.markShownAsRead() else onMarkAllReadClick()
        }
        
        // Add bottom padding to FAB to account for navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.bottomMargin = systemBars.bottom
            view.layoutParams = layoutParams
            insets
        }

        // Swipe to refresh
        mainListContainer = findViewById(R.id.main_subscriptions_list_container)
        mainListContainer.setOnRefreshListener { refreshAllSubscriptions() }
        mainListContainer.setColorSchemeColors(Colors.swipeToRefreshColor(this))

        // Update main list based on viewModel (& its datasource/livedata)
        noEntries = findViewById(R.id.main_no_subscriptions)
        inboxContainer = findViewById(R.id.main_inbox_fragment)
        tabs = findViewById(R.id.main_tabs)
        val startPage = savedInstanceState?.getInt(STATE_TAB) ?: if (repository.getStartPage() == Repository.START_PAGE_TOPICS) TAB_TOPICS else TAB_MESSAGES
        tabs.getTabAt(startPage)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = updatePage()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val onSubscriptionClick = { s: Subscription -> onSubscriptionItemClick(s) }
        val onSubscriptionLongClick = { s: Subscription -> onSubscriptionItemLongClick(s) }

        mainList = findViewById(R.id.main_subscriptions_list)
        adapter = MainAdapter(
            repository,
            onSubscriptionClick,
            onSubscriptionLongClick,
            ResourcesCompat.getDrawable(resources, R.drawable.ic_circle, theme)!!.apply {
                setTint(Colors.primary(this@MainActivity))
            },
            Colors.onPrimary(this)
        )
        mainList.adapter = adapter
        
        // Apply window insets to ensure content is not covered by navigation bar
        mainList.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(mainList) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom)
            insets
        }

        viewModel.list().observe(this) {
            it?.let { subscriptions ->
                // Update main list
                this.subscriptions = subscriptions
                adapter.submitList(subscriptions as MutableList<Subscription>)
                updatePage()

                // Add scrub terms to log (in case it gets exported)
                subscriptions.forEach { s ->
                    Log.addScrubTerm(shortUrl(s.baseUrl), Log.TermType.Domain)
                    Log.addScrubTerm(s.topic)
                }

                showHideBatteryBanner(subscriptions)
                updateStatusDot()
            }
        }

        // Add scrub terms to log (in case it gets exported) // FIXME this should be in Log.getFormatted
        repository.getUsersLiveData().observe(this) {
            it?.let { users ->
                users.forEach { u ->
                    Log.addScrubTerm(shortUrl(u.baseUrl), Log.TermType.Domain)
                    Log.addScrubTerm(u.username, Log.TermType.Username)
                    Log.addScrubTerm(u.password, Log.TermType.Password)
                }
                hasUsers = users.isNotEmpty()
                if (this::menu.isInitialized) menu.findItem(R.id.main_menu_account)?.isVisible = hasUsers
            }
        }

        // Scrub terms for last topics // FIXME this should be in Log.getFormatted
        repository.getLastShareTopics().forEach { topicUrl ->
            maybeSplitTopicUrl(topicUrl)?.let {
                Log.addScrubTerm(shortUrl(it.first), Log.TermType.Domain)
                Log.addScrubTerm(shortUrl(it.second), Log.TermType.Term)
            }
        }

        // React to changes in instant delivery setting
        viewModel.listIdsWithInstantStatus().observe(this) {
            SubscriberServiceManager.refresh(this)
        }

        // Observe connection details and update menu item visibility
        repository.getConnectionDetailsLiveData().observe(this) {
            updateStatusDot()
        }
        repository.getAuthFailedBaseUrlsLiveData().observe(this) {
            updateStatusDot()
        }

        // The status also depends on time: a short drop only turns red after a while
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    updateStatusDot()
                    delay(STATUS_UPDATE_INTERVAL_MILLIS)
                }
            }
        }

        // Battery banner
        val batteryBanner = findViewById<View>(R.id.main_banner_battery) // Banner visibility is toggled in onResume()
        val dontAskAgainButton = findViewById<Button>(R.id.main_banner_battery_dontaskagain)
        val askLaterButton = findViewById<Button>(R.id.main_banner_battery_ask_later)
        val fixNowButton = findViewById<Button>(R.id.main_banner_battery_fix_now)
        dontAskAgainButton.setOnClickListener {
            batteryBanner.visibility = View.GONE
            repository.setBatteryOptimizationsRemindTime(Repository.BATTERY_OPTIMIZATIONS_REMIND_TIME_NEVER)
        }
        askLaterButton.visibility = View.GONE
        fixNowButton.setOnClickListener {
            try {
                Log.d(TAG, "package:$packageName".toUri().toString())
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:$packageName".toUri()
                    )
                )
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            // Hide, at least for now
            val batteryBanner = findViewById<View>(R.id.main_banner_battery)
            batteryBanner.visibility = View.GONE
        }

        // Network state banner
        showHideNoNetworkBanner()
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { showHideNoNetworkBanner() }
            }
            override fun onLost(network: Network) {
                runOnUiThread { showHideNoNetworkBanner() }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        // Hide links that lead to payments, see https://github.com/binwiederhier/ntfy/issues/1463
        val howToLink = findViewById<TextView>(R.id.main_how_to_link)
        howToLink.isVisible = BuildConfig.PAYMENT_LINKS_AVAILABLE

        // Create notification channels right away, so we can configure them immediately after installing the app
        dispatcher?.init()

        // Subscribe to control Firebase channel (so we can re-start the foreground service if it dies)
        messenger.subscribe(ApiService.CONTROL_TOPIC)

        // Darrkkkk mode
        AppCompatDelegate.setDefaultNightMode(repository.getDarkMode())

        // Subscribe to all topics of the configured accounts
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AccountSyncer(this@MainActivity).sync()
            } catch (e: Exception) {
                Log.w(TAG, "Account sync failed: ${e.message}")
            }
        }

        // Background things
        schedulePeriodicPollWorker()
        schedulePeriodicServiceRestartWorker()
        schedulePeriodicDeleteWorker()

        // Permissions
        maybeRequestNotificationPermission()

        // FIXME 2026-05-04: Remove this migration after 1 month
        migrateSubscriptionIconsFromCache()
    }

    private fun maybeRequestNotificationPermission() {
        // Android 13 (SDK 33) requires that we ask for permission to post notifications
        // https://developer.android.com/develop/ui/views/notifications/notification-permission

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        showHideNotificationMenuItems()
        showHideNoNetworkBanner()
        redrawList()
        updatePage()

        // Opening the app must never show a stale state: make sure the service runs, and fetch what it missed
        SubscriberServiceManager.refresh(this)
        lifecycleScope.launch(Dispatchers.IO) {
            ConnectionCheck.checkIfDue(applicationContext)
        }
    }

    private fun showHideBatteryBanner(subscriptions: List<Subscription>) {
        val hasInstantSubscriptions = subscriptions.count { it.instant } > 0
        val batteryRemindTimeReached = repository.getBatteryOptimizationsRemindTime() < System.currentTimeMillis()
        val ignoringOptimizations = isIgnoringBatteryOptimizations(this@MainActivity)
        val showBanner = batteryRemindTimeReached && !ignoringOptimizations
        val batteryBanner = findViewById<View>(R.id.main_banner_battery)
        batteryBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
        Log.d(TAG, "Battery: ignoring optimizations = $ignoringOptimizations (we want this to be true); instant subscriptions = $hasInstantSubscriptions; remind time reached = $batteryRemindTimeReached; banner = $showBanner")
    }

    private fun showHideNoNetworkBanner() {
        val banner = findViewById<View>(R.id.main_banner_no_network)
        banner.visibility = if (isNetworkAvailable(this)) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}", e)
        }
        networkCallback = null
    }

    private fun schedulePeriodicPollWorker() {
        val workerVersion = repository.getPollWorkerVersion()
        val workPolicy = if (workerVersion == PollWorker.VERSION) {
            Log.d(TAG, "Poll worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Poll worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setPollWorkerVersion(PollWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<PollWorker>(POLL_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PollWorker.TAG)
            .addTag(PollWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        Log.d(TAG, "Poll worker: Scheduling period work every $POLL_WORKER_INTERVAL_MINUTES minutes")
        workManager!!.enqueueUniquePeriodicWork(PollWorker.WORK_NAME_PERIODIC_ALL, workPolicy, work)
    }

    private fun schedulePeriodicDeleteWorker() {
        val workerVersion = repository.getDeleteWorkerVersion()
        val workPolicy = if (workerVersion == DeleteWorker.VERSION) {
            Log.d(TAG, "Delete worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Delete worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setDeleteWorkerVersion(DeleteWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val work = PeriodicWorkRequestBuilder<DeleteWorker>(DELETE_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(DeleteWorker.TAG)
            .addTag(DeleteWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        Log.d(TAG, "Delete worker: Scheduling period work every $DELETE_WORKER_INTERVAL_MINUTES minutes")
        workManager!!.enqueueUniquePeriodicWork(DeleteWorker.WORK_NAME_PERIODIC_ALL, workPolicy, work)
    }

    private fun schedulePeriodicServiceRestartWorker() {
        val workerVersion = repository.getAutoRestartWorkerVersion()
        val workPolicy = if (workerVersion == SubscriberService.SERVICE_START_WORKER_VERSION) {
            Log.d(TAG, "ServiceStartWorker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "ServiceStartWorker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setAutoRestartWorkerVersion(SubscriberService.SERVICE_START_WORKER_VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val work = PeriodicWorkRequestBuilder<SubscriberServiceManager.ServiceStartWorker>(SERVICE_START_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(SubscriberService.TAG)
            .addTag(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC)
            .build()
        Log.d(TAG, "ServiceStartWorker: Scheduling period work every $SERVICE_START_WORKER_INTERVAL_MINUTES minutes")
        workManager?.enqueueUniquePeriodicWork(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC, workPolicy, work)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_action_bar, menu)
        this.menu = menu
        menu.findItem(R.id.main_menu_account)?.isVisible = hasUsers
        
        // Tint menu icons based on theme
        val toolbarTextColor = Colors.toolbarTextColor(this, repository.getDynamicColorsEnabled(), isDarkThemeOn(this))
        for (i in 0 until menu.size) {
            menu[i].icon?.setTint(toolbarTextColor)
        }
        
        showHideNotificationMenuItems()
        updatePage()
        updateStatusDot()
        checkSubscriptionsMuted() // This is done here, because then we know that we've initialized the menu
        return true
    }

    private fun checkSubscriptionsMuted(delayMillis: Long = 0L) {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(delayMillis) // Just to be sure we've initialized all the things, we wait a bit ...
            Log.d(TAG, "Checking global and subscription-specific 'muted until' timestamp")

            // Check global
            val changed = repository.checkGlobalMutedUntil()
            if (changed) {
                Log.d(TAG, "Global muted until timestamp expired; updating prefs")
                showHideNotificationMenuItems()
            }

            // Check subscriptions
            var rerenderList = false
            repository.getSubscriptions().forEach { subscription ->
                val mutedUntilExpired = subscription.mutedUntil > 1L && System.currentTimeMillis()/1000 > subscription.mutedUntil
                if (mutedUntilExpired) {
                    Log.d(TAG, "Subscription ${subscription.id}: Muted until timestamp expired, updating subscription")
                    val newSubscription = subscription.copy(mutedUntil = 0L)
                    repository.updateSubscription(newSubscription)
                    rerenderList = true
                }
            }
            if (rerenderList) {
                mainList.post {
                    redrawList()
                }
            }
        }
    }

    private fun showHideNotificationMenuItems() {
        if (!this::menu.isInitialized) {
            return
        }
        val mutedUntilSeconds = repository.getGlobalMutedUntil()
        runOnUiThread {
            // Show/hide menu items based on build config
            val rateAppItem = menu.findItem(R.id.main_menu_rate)
            val docsItem = menu.findItem(R.id.main_menu_docs)
            val reportBugItem = menu.findItem(R.id.main_menu_report_bug)
            rateAppItem.isVisible = BuildConfig.RATE_APP_AVAILABLE
            docsItem.isVisible = BuildConfig.PAYMENT_LINKS_AVAILABLE // Google Payments Policy, see https://github.com/binwiederhier/ntfy/issues/1463
            reportBugItem.isVisible = false // Personal fork: bugs belong in this repo, not in the upstream issue tracker

            // Pause notification icons
            val notificationsEnabledItem = menu.findItem(R.id.main_menu_notifications_enabled)
            val notificationsDisabledUntilItem = menu.findItem(R.id.main_menu_notifications_disabled_until)
            val notificationsDisabledForeverItem = menu.findItem(R.id.main_menu_notifications_disabled_forever)
            notificationsEnabledItem?.isVisible = mutedUntilSeconds == 0L
            notificationsDisabledForeverItem?.isVisible = mutedUntilSeconds == 1L
            notificationsDisabledUntilItem?.isVisible = mutedUntilSeconds > 1L
            if (mutedUntilSeconds > 1L) {
                val formattedDate = formatDateShort(mutedUntilSeconds)
                notificationsDisabledUntilItem?.title = getString(R.string.main_menu_notifications_disabled_until, formattedDate)
            }
        }
    }

    private fun updateStatusDot() {
        if (!this::menu.isInitialized) return
        val density = resources.displayMetrics.density
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ConnectionStatus.level(this@MainActivity, repository, subscriptions).color)
            setStroke((2 * density).toInt(), toolbarIconColor())
            setSize((16 * density).toInt(), (16 * density).toInt())
        }
        menu.findItem(R.id.main_menu_status)?.icon = InsetDrawable(dot, (4 * density).toInt())
    }

    private fun toolbarIconColor() = Colors.toolbarTextColor(this, repository.getDynamicColorsEnabled(), isDarkThemeOn(this))

    /** Which page is shown, and what the buttons above and below it mean there. */
    private fun updatePage() {
        val hasSubscriptions = !subscriptions.isEmpty()
        inboxContainer.isVisible = inboxShown
        mainListContainer.isVisible = !inboxShown && hasSubscriptions
        noEntries.isVisible = !inboxShown && !hasSubscriptions
        updateFab()
        updateFilterIcon()
    }

    private fun updateFab() {
        val anyUnread = if (inboxShown) {
            inbox?.shownUnread?.isNotEmpty() == true
        } else {
            subscriptions.any { it.newCount > 0 }
        }
        fab.isVisible = actionMode == null && anyUnread
    }

    private fun updateFilterIcon() {
        if (!this::menu.isInitialized) return
        val item = menu.findItem(R.id.main_menu_filter) ?: return
        item.isVisible = inboxShown
        item.setIcon(if (inbox?.filter?.isActive == true) R.drawable.ic_filter_list_active_white_24dp else R.drawable.ic_filter_list_white_24dp)
        item.icon?.setTint(toolbarIconColor())
    }

    fun onInboxChanged() {
        updateFab()
        updateFilterIcon()
    }

    fun openTopic(subscription: Subscription) {
        onSubscriptionItemClick(subscription)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tabs.selectedTabPosition)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_menu_notifications_enabled -> {
                onNotificationSettingsClick(enable = false)
                true
            }
            R.id.main_menu_notifications_disabled_forever -> {
                onNotificationSettingsClick(enable = true)
                true
            }
            R.id.main_menu_notifications_disabled_until -> {
                onNotificationSettingsClick(enable = true)
                true
            }
            R.id.main_menu_status -> {
                ConnectionStatus.showDetails(this, repository, subscriptions)
                true
            }
            R.id.main_menu_filter -> {
                inbox?.openFilter()
                true
            }
            R.id.main_menu_subscribe -> {
                onSubscribeButtonClick()
                true
            }
            R.id.main_menu_publish -> {
                onPublishClick()
                true
            }
            R.id.main_menu_start_page -> {
                onStartPageClick()
                true
            }
            R.id.main_menu_account -> {
                AccountActivity.start(this)
                true
            }
            R.id.main_menu_quit -> {
                onQuitClick()
                true
            }
            R.id.main_menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.main_menu_report_bug -> {
                startActivity(
                    Intent(Intent.ACTION_VIEW, getString(R.string.main_menu_report_bug_url).toUri())
                )
                true
            }
            R.id.main_menu_rate -> {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
                    )
                } catch (_: ActivityNotFoundException) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
                    )
                }
                true
            }
            R.id.main_menu_docs -> {
                startActivity(
                    Intent(Intent.ACTION_VIEW, getString(R.string.main_menu_docs_url).toUri())
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onPublishClick() {
        val subscriptions = this.subscriptions.filter { it.upAppId == null }
        if (subscriptions.isEmpty()) {
            Toast.makeText(this, R.string.publish_no_topics, Toast.LENGTH_LONG).show()
            return
        }
        val names = subscriptions.map { displayName(appBaseUrl, it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.publish_choose_topic)
            .setItems(names.toTypedArray()) { _, which ->
                val subscription = subscriptions[which]
                PublishFragment
                    .newInstance(subscription.baseUrl, subscription.topic, names[which])
                    .show(supportFragmentManager, PublishFragment.TAG)
            }
            .show()
    }

    private fun onStartPageClick() {
        val pages = listOf(Repository.START_PAGE_MESSAGES, Repository.START_PAGE_TOPICS)
        val labels = arrayOf(getString(R.string.tab_messages), getString(R.string.tab_topics))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_menu_start_page)
            .setSingleChoiceItems(labels, pages.indexOf(repository.getStartPage())) { dialog, which ->
                repository.setStartPage(pages[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun onQuitClick() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.main_quit_dialog_message)
            .setPositiveButton(R.string.main_quit_dialog_quit) { _, _ ->
                repository.setAppQuit(true)
                stopService(Intent(this, SubscriberService::class.java))
                finishAndRemoveTask()
            }
            .setNegativeButton(R.string.main_quit_dialog_cancel, null)
            .show()
    }

    private fun onMarkAllReadClick() {
        lifecycleScope.launch(Dispatchers.IO) {
            MarkAllRead.run(this@MainActivity)
        }
    }

    private fun onNotificationSettingsClick(enable: Boolean) {
        if (!enable) {
            Log.d(TAG, "Showing global notification settings dialog")
            val notificationFragment = NotificationFragment()
            notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)
        } else {
            Log.d(TAG, "Re-enabling global notifications")
            onNotificationMutedUntilChanged(Repository.MUTED_UNTIL_SHOW_ALL)
        }
    }

    override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
        repository.setGlobalMutedUntil(mutedUntilTimestamp)
        showHideNotificationMenuItems()
        runOnUiThread {
            redrawList() // Update the "muted until" icons
            when (mutedUntilTimestamp) {
                0L -> Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_enabled_toast_message), Toast.LENGTH_LONG).show()
                1L -> Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_muted_forever_toast_message), Toast.LENGTH_LONG).show()
                else -> {
                    val formattedDate = formatDateShort(mutedUntilTimestamp)
                    Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_muted_until_toast_message, formattedDate), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onSubscribeButtonClick() {
        val newFragment = AddFragment()
        newFragment.show(supportFragmentManager, AddFragment.TAG)
    }

    override fun onSubscribe(topic: String, baseUrl: String, instant: Boolean) {
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)} (instant = $instant)")

        // Add subscription to database
        val subscription = Subscription(
            id = randomSubscriptionId(),
            baseUrl = baseUrl,
            topic = topic,
            instant = instant,
            dedicatedChannels = false,
            mutedUntil = 0,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = null,
            upConnectorToken = null,
            displayName = null,
            totalCount = 0,
            newCount = 0,
            lastActive = Date().time/1000
        )
        viewModel.add(subscription)

        // Subscribe to Firebase topic if ntfy.sh (even if instant, just to be sure!)
        if (baseUrl == appBaseUrl) {
            Log.d(TAG, "Subscribing to Firebase topic $topic")
            messenger.subscribe(topic)
        }

        // Fetch cached messages
        lifecycleScope.launch(Dispatchers.IO) {
            AccountSyncer(this@MainActivity).onLocalSubscribe(baseUrl, topic)
            try {
                val notifications = poller.poll(subscription)
                notifications.forEach { notification ->
                    if (notification.icon != null) {
                        DownloadManager.enqueue(this@MainActivity, notification.id, userAction = false, DownloadType.ICON)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to fetch notifications: ${e.message}", e)
            }
        }

        // Switch to detail view after adding it
        onSubscriptionItemClick(subscription)
    }

    private fun onSubscriptionItemClick(subscription: Subscription) {
        if (actionMode != null) {
            handleActionModeClick(subscription)
        } else if (subscription.upAppId != null) { // UnifiedPush
            startDetailSettingsView(subscription)
        } else {
            startDetailView(subscription)
        }
    }

    private fun onSubscriptionItemLongClick(subscription: Subscription) {
        if (actionMode == null) {
            beginActionMode(subscription)
        }
    }

    fun refreshAllSubscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AccountSyncer(this@MainActivity).sync()
            } catch (e: Exception) {
                Log.w(TAG, "Account sync failed: ${e.message}")
            }
            Log.d(TAG, "Polling for new notifications")
            var errors = 0
            var errorMessage = "" // First error
            var newNotificationsCount = 0
            val missed = mutableListOf<Subscription>()
            repository.getSubscriptions().forEach { subscription ->
                Log.d(TAG, "Polling subscription: $subscription")
                try {
                    val newNotifications = poller.poll(subscription)
                    newNotificationsCount += newNotifications.size
                    newNotifications.forEach { notification ->
                        dispatcher?.dispatch(subscription, notification)
                    }
                    if (newNotifications.isNotEmpty()) missed.add(subscription)
                } catch (e: Exception) {
                    val topic = displayName(appBaseUrl, subscription)
                    if (errorMessage == "") errorMessage = "$topic: ${e.message}"
                    errors++
                }
            }
            ConnectionCheck.reconnectIfMissed(applicationContext, missed)
            val toastMessage = if (errors > 0) {
                getString(R.string.refresh_message_error, errors, errorMessage)
            } else if (newNotificationsCount == 0) {
                getString(R.string.refresh_message_no_results)
            } else {
                getString(R.string.refresh_message_result, newNotificationsCount)
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_LONG).show()
                mainListContainer.isRefreshing = false
                inbox?.setRefreshing(false)
            }
            Log.d(TAG, "Finished polling for new notifications")
        }
    }

    private fun startDetailView(subscription: Subscription) {
        Log.d(TAG, "Entering detail view for subscription $subscription")

        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
        intent.putExtra(EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        intent.putExtra(EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        startActivity(intent)
    }

    private fun startDetailSettingsView(subscription: Subscription) {
        Log.d(TAG, "Opening subscription settings for ${topicShortUrl(subscription.baseUrl, subscription.topic)}")

        val intent = Intent(this, DetailSettingsActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
        startActivity(intent)
    }

    private fun handleActionModeClick(subscription: Subscription) {
        adapter.toggleSelection(subscription.id)
        if (adapter.selected.isEmpty()) {
            finishActionMode()
        } else {
            actionMode!!.title = adapter.selected.size.toString()
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(DetailActivity.TAG, "Showing multi-delete dialog for selected items")

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.main_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.main_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { subscriptionId -> viewModel.remove(this, subscriptionId) }
                finishActionMode()
            }
            .setNegativeButton(R.string.main_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton()
        }
        dialog.show()
    }

    private fun beginActionMode(subscription: Subscription) {
        actionMode = startSupportActionMode(actionModeCallback)
        adapter.toggleSelection(subscription.id)
        updateFab()
    }

    private fun finishActionMode() {
        actionMode!!.finish()
        endActionModeAndRedraw()
    }

    private fun endActionModeAndRedraw() {
        actionMode = null
        adapter.selected.clear()
        redrawList()
        updateFab()
    }

    private fun redrawList() {
        if (!this::mainList.isInitialized) {
            return
        }
        adapter.notifyItemRangeChanged(0, adapter.currentList.size)
    }

    // FIXME 2026-05-04: Remove this migration after 1 month
    private fun migrateSubscriptionIconsFromCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(5_000) // 5 seconds
            try {
                val oldDir = File(cacheDir, SUBSCRIPTION_ICONS)
                if (!oldDir.exists() || !oldDir.isDirectory) return@launch
                val newDir = File(filesDir, SUBSCRIPTION_ICONS)
                if (!newDir.exists()) newDir.mkdirs()
                oldDir.listFiles()?.forEach { oldFile ->
                    val newFile = File(newDir, oldFile.name)
                    if (newFile.exists()) {
                        oldFile.delete()
                        return@forEach
                    }
                    if (oldFile.renameTo(newFile)) {
                        val subscriptionId = oldFile.name.toLongOrNull() ?: return@forEach
                        val newUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            BuildConfig.APPLICATION_ID + ".provider",
                            newFile
                        )
                        repository.updateSubscriptionIcon(subscriptionId, newUri.toString())
                    }
                }
                oldDir.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate subscription icons", e)
            }
        }
    }

    companion object {
        const val TAG = "NtfyMainActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "subscriptionBaseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "subscriptionTopic"
        const val EXTRA_SUBSCRIPTION_DISPLAY_NAME = "subscriptionDisplayName"
        const val EXTRA_SUBSCRIPTION_INSTANT = "subscriptionInstant"
        const val EXTRA_SUBSCRIPTION_MUTED_UNTIL = "subscriptionMutedUntil"
        const val ONE_DAY_MILLIS = 86400000L
        private const val TAB_MESSAGES = 0
        private const val TAB_TOPICS = 1
        private const val STATE_TAB = "tab"
        private const val STATUS_UPDATE_INTERVAL_MILLIS = 15_000L

        // As per documentation: The minimum repeat interval that can be defined is 15 minutes
        // (same as the JobScheduler API), but in practice 15 doesn't work. Using 16 here.
        // Thanks to varunon9 (https://gist.github.com/varunon9/f2beec0a743c96708eb0ef971a9ff9cd) for this!

        const val POLL_WORKER_INTERVAL_MINUTES = 16L // Catches a live connection that died unnoticed, see ConnectionCheck
        const val DELETE_WORKER_INTERVAL_MINUTES = 8 * 60L
        const val SERVICE_START_WORKER_INTERVAL_MINUTES = 30L // Only a safety net; the check itself is cheap
    }
}
