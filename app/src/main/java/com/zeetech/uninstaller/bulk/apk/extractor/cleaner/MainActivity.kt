package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

// Logs routed through Logger; android.util.Log imports removed to avoid accidental debug logging

import android.app.AppOpsManager
import android.app.Activity
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.Charcoal
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.EmeraldGreen
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.LogoPurple
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.UninstallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import android.view.ViewGroup
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.PremiumGold
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.PremiumCardBg
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.DarkBackground
import com.google.android.play.core.install.model.ActivityResult

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: AppViewModel
    private var pendingHistoryApp: AppInfo? = null
    private var isAlwaysOnUninstall = false
    lateinit var updateManager: UpdateManager
    lateinit var ratingManager: RatingManager
    private var showRatingDialog by mutableStateOf(false)


    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK || it.resultCode == RESULT_CANCELED) {
            // Android doesn't return a reliable SUCCESS for uninstalls, 
            // so we refresh and check if the package is gone.
            viewModel.refreshList { isGone ->
                if (isAlwaysOnUninstall) {
                    AdManager.onUninstallConfirmed(onDismiss = {
                        showActionRatingPrompt()
                    })
                } else {
                    AdManager.onSingleUninstallFromBar(onDismiss = {
                        showActionRatingPrompt()
                    })
                }
                
                if (isGone) {
                    pendingHistoryApp?.let { app -> viewModel.addToHistory(app) }
                }
                
                pendingHistoryApp = null
                isAlwaysOnUninstall = false
            }
        } else {
            pendingHistoryApp = null
        }
    }

    internal val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Logger.e("MainActivity", "Update flow failed! Result code: ${result.resultCode}")
        }
    }

    private val packageRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                viewModel.refreshList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        AdManager.initialize(this)
        BillingManager.initialize(this)
        FeatureFlags.initialize()
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]

        // Initialize Update Manager
        updateManager = UpdateManager(this)
        ratingManager = RatingManager(this)
        ratingManager.initializeFirstLaunch()

        // Fetch and log Firebase Cloud Messaging Token for testing
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Logger.d("MainActivity", "FCM Registration Token: ${task.result}")
            } else {
                Logger.w("MainActivity", "Fetching FCM registration token failed: ${task.exception?.message}")
            }
        }

        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageRemovedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageRemovedReceiver, filter)
        }

        // App Open Ad & Rating - Foreground Resumption Logic
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Auto-refresh lists and detect uninstalled apps on resume
                viewModel.refreshHistory()

                // Capture cold-start flag immediately and flip it
                val isColdStart = AdManager.appLifecycleFirstStart
                AdManager.appLifecycleFirstStart = false

                // Register what happens when the app open ad is dismissed.
                // This fires from AdManager.onAdDismissedFullScreenContent, so it works
                // regardless of which path showed the ad (fast-load vs wait-loop).
                AdManager.onAppOpenAdDismissed = {
                    if (isColdStart) {
                        lifecycleScope.launch {
                            delay(600)
                            viewModel.triggerPaywall()
                        }
                    } else {
                        showForegroundRatingPrompt()
                    }
                }

                lifecycleScope.launch {
                    if (AdManager.isAdShowing()) return@launch

                    // Cold start: wait up to 5 sec for ad before giving up
                    if (isColdStart) {
                        var timeout = 0
                        while (!AdManager.isAppOpenReady() && timeout < 50) {
                            delay(100)
                            timeout++
                        }
                    }

                    AdManager.showAppOpenAdIfAvailable()
                }
            }
        })

        // Automated Storage Sweep on Launch
        if (viewModel.scanOnLaunch.value && viewModel.hasAllFilesAccess()) {
            viewModel.startDeepClean()
        }

        // Initialize WorkManager manually (default disabled in Manifest to prevent crash)
        try {
            WorkManager.initialize(
                this,
                Configuration.Builder()
                    // Reduce logging to errors only to avoid debug noise
                    .setMinimumLoggingLevel(android.util.Log.ERROR)
                    .build()
            )
        } catch (e: Exception) {
            // Already initialized or initialization failed
        }

        // Schedule background storage alert worker if enabled
        if (viewModel.storageAlertsEnabled.value) {
            StorageAlertWorker.schedule(this)
        }
        
        enableEdgeToEdge()

        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(true) }
            
            val storageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                // Check if permission was granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        viewModel.startDeepClean()
                    }
                }
            }

            // Request POST_NOTIFICATIONS on Android 13+ on first launch
            val notificationPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted or denied — worker checks itself before posting */ }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            UninstallerTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UninstallerApp(
                        viewModel = viewModel,
                        ratingManager = ratingManager,
                        isDarkTheme = darkTheme,
                        onThemeToggle = { darkTheme = !darkTheme },
                        onUninstallRequest = { app, alwaysOn ->
                            pendingHistoryApp = app
                            isAlwaysOnUninstall = alwaysOn
                            val intent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:${app.packageName}")
                            }
                            uninstallLauncher.launch(intent)
                        },
                        onRequestStoragePermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    intent.addCategory("android.intent.category.DEFAULT")
                                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                                    storageLauncher.launch(intent)
                                } catch (e: Exception) {
                                    val intent = Intent()
                                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                    storageLauncher.launch(intent)
                                }
                            }
                        }
                    )
                }

                // Show Rating Dialog inside the Composable tree
                if (showRatingDialog) {
                    RatingAvailableDialog(
                        onDismiss = { showRatingDialog = false },
                        onRateNow = { 
                            ratingManager.launchStore(this@MainActivity)
                            showRatingDialog = false
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageRemovedReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    // onActivityResult for UpdateManager is now handled by ActivityResultLauncher

    /**
     * Helper to show the rating prompt for ACTIONS (Uninstall, Clean, Extract).
     */
    fun showActionRatingPrompt() {
        if (::ratingManager.isInitialized && ratingManager.canShowActionPrompt()) {
            runOnUiThread {
                showRatingDialog = true
            }
            ratingManager.markAsAsked()
        }
    }

    /**
     * Helper to show the rating prompt for FOREGROUND/APP-OPEN.
     */
    fun showForegroundRatingPrompt() {
        if (::ratingManager.isInitialized && ratingManager.canShowForegroundPrompt()) {
            runOnUiThread {
                showRatingDialog = true
            }
            ratingManager.markForegroundPromptShown()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh AdManager's weak reference to Activity on every resume
        AdManager.bindActivity(this)

        // Resume any ongoing IMMEDIATE updates (Google Play)
        if (::updateManager.isInitialized) {
            updateManager.checkOngoingUpdate(updateLauncher)
        }
        // Icons are now preserved on disk and handled by Coil.
    }

    override fun onStart() {
        super.onStart()
        // Bind activity earlier (onStart) so ProcessLifecycleOwner's onStart observers
        // can access a valid Activity when attempting to show App Open ads.
        AdManager.bindActivity(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Icons are now stored on disk and managed by Coil, so no RAM clearing needed here.
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val uiState: StateFlow<AppUiState> = _uiState

    val largeScanPromptEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    private var scanDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun resumeScan(continueAll: Boolean) {
        scanDeferred?.complete(continueAll)
    }

    fun toggleCacheItem(file: java.io.File) {
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val newCacheItems = currentState.result.cacheItems.map {
                if (it.file == file) it.copy(isSelected = !it.isSelected) else it
            }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(cacheItems = newCacheItems))
        }
    }

    fun toggleDuplicateFile(file: java.io.File) {
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val newGroups = currentState.result.duplicateGroups.map { group ->
                if (group.files.any { it.file == file }) {
                    val newFiles = group.files.map {
                        if (it.file == file) it.copy(isSelected = !it.isSelected) else it
                    }
                    group.copy(files = newFiles)
                } else {
                    group
                }
            }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(duplicateGroups = newGroups))
        }
    }

    fun toggleLargeFile(file: java.io.File) {
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val newLargeFiles = currentState.result.largeFiles.map {
                if (it.file == file) it.copy(isSelected = !it.isSelected) else it
            }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(largeFiles = newLargeFiles))
        }
    }

    fun selectAllCache(select: Boolean) {
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val newCache = currentState.result.cacheItems.map { it.copy(isSelected = select) }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(cacheItems = newCache))
        }
    }

    fun selectAllDuplicates(select: Boolean) {
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val newGroups = currentState.result.duplicateGroups.map { group ->
                val newFiles = group.files.mapIndexed { idx, it ->
                    if (select) {
                        it.copy(isSelected = (idx > 0))
                    } else {
                        it.copy(isSelected = false)
                    }
                }
                group.copy(files = newFiles)
            }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(duplicateGroups = newGroups))
        }
    }
    private val _largeFileThreshold = MutableStateFlow(50L * 1024 * 1024) // 50MB default
    val largeFileThreshold: kotlinx.coroutines.flow.StateFlow<Long> = _largeFileThreshold

    fun setLargeFileThreshold(bytes: Long) {
        _largeFileThreshold.value = bytes
        val currentState = _uiState.value
        if (currentState is AppUiState.ShredderReady) {
            val resetLarge = currentState.result.largeFiles.map { it.copy(isSelected = false) }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(largeFiles = resetLarge))
        }
    }

    fun selectAllLargeFiles(select: Boolean) {
        val currentState = _uiState.value
        val threshold = _largeFileThreshold.value
        if (currentState is AppUiState.ShredderReady) {
            val newLarge = currentState.result.largeFiles.map {
                // Use pre-stored size to avoid disk I/O on the main thread
                if (it.size >= threshold) {
                    it.copy(isSelected = select)
                } else {
                    it
                }
            }
            _uiState.value = AppUiState.ShredderReady(currentState.result.copy(largeFiles = newLarge))
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    // Set of package names whose icons are currently cached on disk
    private val _cachedIcons = MutableStateFlow<Set<String>>(emptySet())
    val cachedIcons: StateFlow<Set<String>> = _cachedIcons

    private val _uninstalledHistory = MutableStateFlow<List<HistoryAppRecord>>(emptyList())
    val uninstalledHistory: StateFlow<List<HistoryAppRecord>> = _uninstalledHistory

    // Cached success state — avoids reloading when navigating back from a popup
    private var cachedSuccessState: AppUiState.Success? = null

    private val prefs = application.getSharedPreferences("surgical_uninstaller_prefs", Context.MODE_PRIVATE)
    
    private val _sortBy = MutableStateFlow(prefs.getString("sort_by", "Name") ?: "Name")
    val sortBy: StateFlow<String> = _sortBy

    private val _isAscending = MutableStateFlow(prefs.getBoolean("is_ascending", true))
    val isAscending: StateFlow<Boolean> = _isAscending

    private val _storageThreshold = MutableStateFlow(prefs.getFloat("storage_threshold", 0.9f))
    val storageThreshold: StateFlow<Float> = _storageThreshold

    private val _scanOnLaunch = MutableStateFlow(prefs.getBoolean("scan_on_launch", false))
    val scanOnLaunch: StateFlow<Boolean> = _scanOnLaunch

    private val _extractionPath = MutableStateFlow(
        prefs.getString("extraction_path",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Uninstaller_Backups"
        ) ?: ""
    )
    val extractionPath: StateFlow<String> = _extractionPath

    fun updateExtractionPath(path: String) {
        _extractionPath.value = path
        prefs.edit().putString("extraction_path", path).apply()
    }

    private val _storageAlertsEnabled = MutableStateFlow(prefs.getBoolean("storage_alerts_enabled", true))
    val storageAlertsEnabled: StateFlow<Boolean> = _storageAlertsEnabled

    fun toggleStorageAlerts(enabled: Boolean) {
        _storageAlertsEnabled.value = enabled
        prefs.edit().putBoolean("storage_alerts_enabled", enabled).apply()
        val ctx = getApplication<Application>()
        if (enabled) StorageAlertWorker.schedule(ctx) else StorageAlertWorker.cancel(ctx)
    }

    private val _triggerPaywallSignal = MutableStateFlow(false)
    val triggerPaywallSignal: StateFlow<Boolean> = _triggerPaywallSignal
    fun triggerPaywall() { _triggerPaywallSignal.value = true }
    fun consumePaywallTrigger() { _triggerPaywallSignal.value = false }

    val appVersionName: String = try {
        val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        pInfo.versionName ?: "2.0.1"
    } catch (e: Exception) { "2.0.1" }

    val appVersionCode: Int = try {
        val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode.toInt()
        } else {
            pInfo.versionCode
        }
    } catch (e: Exception) { 21 }

    private var discoveredJunk = listOf<File>()
    private var scanJob: Job? = null

    init {
        loadApps()
        loadHistory()
    }

    private fun loadHistory() {
        val json = prefs.getString("uninstalled_history", null)
        if (json != null) {
            try {
                val list = mutableListOf<HistoryAppRecord>()
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(HistoryAppRecord(o.getString("n"), o.getString("p"), o.getLong("t")))
                }
                _uninstalledHistory.value = list.sortedByDescending { it.timestamp }
            } catch (e: Exception) { _uninstalledHistory.value = emptyList() }
        }
    }

    private fun saveHistory(list: List<HistoryAppRecord>) {
        val json = org.json.JSONArray().also { arr ->
            list.forEach { r ->
                arr.put(org.json.JSONObject().put("n", r.name).put("p", r.packageName).put("t", r.timestamp))
            }
        }.toString()
        prefs.edit().putString("uninstalled_history", json).apply()
    }

    fun addToHistory(app: AppInfo) {
        val newList = _uninstalledHistory.value.toMutableList()
        // Avoid duplicates
        newList.removeAll { it.packageName == app.packageName }
        newList.add(0, HistoryAppRecord(app.name, app.packageName, System.currentTimeMillis()))
        _uninstalledHistory.value = newList
        saveHistory(newList)
    }

    fun clearHistory() {
        val context = getApplication<Application>()
        val iconsDir = File(context.cacheDir, "icons_cache")
        val toClear = _uninstalledHistory.value

        // Add all cleared packages to the permanent dismissed blocklist
        val dismissed = loadDismissedPackages().toMutableSet()
        toClear.forEach { dismissed.add(it.packageName) }
        saveDismissedPackages(dismissed)

        // Delete icon cache files on IO thread (storage cleanup, non-critical)
        viewModelScope.launch(Dispatchers.IO) {
            toClear.forEach { record ->
                File(iconsDir, "${record.packageName}.png").apply { if (exists()) delete() }
            }
            val remaining = iconsDir.listFiles()
                ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
            _cachedIcons.value = remaining
        }

        _uninstalledHistory.value = emptyList()
        prefs.edit().remove("uninstalled_history").apply()
    }

    // -----------------------------------------------------------------------
    // External Uninstall Detection
    // -----------------------------------------------------------------------

    /** Persists a packageName → appName map so we can name orphaned apps later. */
    private fun saveAppNameMapping(apps: List<AppInfo>) {
        val json = org.json.JSONArray().also { arr ->
            apps.forEach { app ->
                arr.put(org.json.JSONObject().put("p", app.packageName).put("n", app.name))
            }
        }.toString()
        prefs.edit().putString("app_name_map", json).apply()
    }

    /** Returns the saved packageName → appName map from the last scan. */
    private fun loadAppNameMapping(): Map<String, String> {
        val raw = prefs.getString("app_name_map", null) ?: return emptyMap()
        return try {
            val map = mutableMapOf<String, String>()
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("p")] = o.getString("n")
            }
            map
        } catch (e: Exception) { emptyMap() }
    }

    /** Packages the user has explicitly cleared — never auto-add these again. */
    private fun loadDismissedPackages(): Set<String> {
        val raw = prefs.getString("dismissed_packages", null) ?: return emptySet()
        return raw.split("|||").filter { it.isNotBlank() }.toSet()
    }

    private fun saveDismissedPackages(set: Set<String>) {
        prefs.edit().putString("dismissed_packages", set.joinToString("|||")).apply()
    }

    /**
     * On app open / manual refresh: compare icon cache files (= apps seen before)
     * with currently installed packages. Any gap = externally uninstalled.
     * Skips packages already in history OR in the dismissed blocklist.
     */
    private fun detectExternallyUninstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val iconsDir = File(context.cacheDir, "icons_cache")
            if (!iconsDir.exists()) return@launch

            // Packages for whom we have saved icons (previously seen)
            val cachedPackages = iconsDir.listFiles()
                ?.filter { it.extension == "png" }
                ?.map { it.nameWithoutExtension }
                ?.toSet() ?: return@launch

            if (cachedPackages.isEmpty()) return@launch

            // Currently installed packages
            val pm = context.packageManager
            val installedPackages = pm.getInstalledApplications(0)
                .map { it.packageName }.toSet()

            // Gap = cached but no longer installed
            val orphaned = cachedPackages - installedPackages
            if (orphaned.isEmpty()) return@launch

            // Skip packages already in history OR dismissed by user
            val alreadyInHistory = _uninstalledHistory.value.map { it.packageName }.toSet()
            val dismissed = loadDismissedPackages()
            val toAdd = orphaned - alreadyInHistory - dismissed
            if (toAdd.isEmpty()) return@launch

            // Look up display names from the saved mapping
            val nameMap = loadAppNameMapping()

            val newEntries = toAdd.map { pkg ->
                HistoryAppRecord(
                    name = nameMap[pkg] ?: pkg, // fallback to packageName if name not found
                    packageName = pkg,
                    timestamp = System.currentTimeMillis()
                )
            }

            val merged = (newEntries + _uninstalledHistory.value)
                .sortedByDescending { it.timestamp }
            _uninstalledHistory.value = merged
            saveHistory(merged)
        }
    }

    fun updateSortPreference(newSortBy: String, newIsAscending: Boolean) {
        _sortBy.value = newSortBy
        _isAscending.value = newIsAscending
        prefs.edit().putString("sort_by", newSortBy).putBoolean("is_ascending", newIsAscending).apply()
    }

    fun updateStorageThreshold(threshold: Float) {
        _storageThreshold.value = threshold
        prefs.edit().putFloat("storage_threshold", threshold).apply()
    }

    fun toggleScanOnLaunch(enabled: Boolean) {
        _scanOnLaunch.value = enabled
        prefs.edit().putBoolean("scan_on_launch", enabled).apply()
    }

    fun hasUsageAccess(): Boolean {
        val appOps = getApplication<Application>().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getApplication<Application>().packageName)
        } else {
            appOps.checkOpNoThrow(OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getApplication<Application>().packageName)
        }
        return mode == MODE_ALLOWED
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Prior to R, standard permissions were enough for what we did
        }
    }

    fun refreshList(onCompletion: ((Boolean) -> Unit)? = null) {
        loadApps(force = true, onCompletion = onCompletion)   // Force reload after uninstall
    }

    /** Called when Refresh is tapped on the History screen.
     *  Reloads installed apps (updates name map) then re-runs external uninstall detection. */
    fun refreshHistory() {
        loadApps(force = true, onCompletion = {
            detectExternallyUninstalled()
        })
    }

    private fun getMD5OfFirstMB(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1024 * 1024)
            java.io.FileInputStream(file).use { fis ->
                val bytesRead = fis.read(buffer)
                if (bytesRead > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val messageDigest = digest.digest()
            val hexString = StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) h = "0" + h
                hexString.append(h)
            }
            hexString.toString()
        } catch (e: Exception) {
            file.absolutePath
        }
    }

    private fun isTargetFileType(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "mp4", "mkv", "avi", "3gp", "webm", "mov",
            "mp3", "wav", "ogg", "m4a", "flac",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf",
            "apk"
        )
    }

    fun startDeepClean() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()

            // ── Phase 1: Analyzing root shadow folders (0%→10%) ──────────────────
            _uiState.emit(AppUiState.Scanning(0.02f, app.getString(R.string.state_analyzing_buffers)))

            val junkFiles = mutableListOf<File>()
            var totalBytes = 0L
            val pm = getApplication<Application>().packageManager
            val installedPackageNames = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            val externalRoot = Environment.getExternalStorageDirectory()

            val rootDirs = externalRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
            rootDirs.forEachIndexed { idx, file ->
                val name = file.name.lowercase()
                if (name.startsWith("com.") || name.startsWith("org.") || name.startsWith("net.") || name.startsWith(".")) {
                    if (!installedPackageNames.contains(name) && !listOf(".android", ".thumbnails").contains(name)) {
                        val size = calculateFolderSize(file)
                        if (size > 0) synchronized(junkFiles) { junkFiles.add(file); totalBytes += size }
                    }
                }
                // Progress 2%→10%
                if (idx % 5 == 0) {
                    val p = 0.02f + (idx.toFloat() / rootDirs.size.coerceAtLeast(1)) * 0.08f
                    _uiState.emit(AppUiState.Scanning(p, app.getString(R.string.state_analyzing_buffers)))
                }
            }

            // ── Phase 2: High-yield junk paths (10%→15%) ─────────────────────────
            _uiState.emit(AppUiState.Scanning(0.10f, app.getString(R.string.state_analyzing_buffers)))
            val globalJunkPaths = listOf(
                "DCIM/.thumbnails", "Pictures/.thumbnails", ".thumbnails", "LOST.DIR",
                "Telegram/Telegram Images/cache", "Telegram/Telegram Video/cache",
                "MIUI/debug_log", "Download/.tmp"
            )
            globalJunkPaths.forEachIndexed { idx, path ->
                val target = File(externalRoot, path)
                if (target.exists()) {
                    val size = calculateFolderSize(target)
                    if (size > 0) synchronized(junkFiles) { junkFiles.add(target); totalBytes += size }
                }
                val p = 0.10f + (idx.toFloat() / globalJunkPaths.size) * 0.05f
                _uiState.emit(AppUiState.Scanning(p, app.getString(R.string.state_analyzing_buffers)))
            }

            // ── Phase 3: Android/data and Android/obb scan (15%→35%) ─────────────
            _uiState.emit(AppUiState.Scanning(0.15f, app.getString(R.string.state_surgical_scan)))
            val dataRoots = listOf("Android/data", "Android/obb")
            dataRoots.forEachIndexed { rootIdx, rootPath ->
                val rootDir = File(externalRoot, rootPath)
                if (rootDir.exists()) {
                    val apps = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    val totalApps = apps.size
                    apps.forEachIndexed { index, appDir ->
                        if (index % 10 == 0) {
                            // Phase 3 spans 15%→35%; split across the two data roots
                            val rootOffset = rootIdx * 0.10f
                            val p = 0.15f + rootOffset + (index.toFloat() / totalApps.coerceAtLeast(1)) * 0.10f
                            _uiState.emit(AppUiState.Scanning(p, app.getString(R.string.state_surgical_scan)))
                        }
                        if (!installedPackageNames.contains(appDir.name)) {
                            val size = calculateFolderSize(appDir)
                            if (size > 0) synchronized(junkFiles) { junkFiles.add(appDir); totalBytes += size }
                        } else {
                            listOf("cache", ".cache", "code_cache").forEach { cName ->
                                val target = File(appDir, cName)
                                if (target.exists()) {
                                    val size = calculateFolderSize(target)
                                    if (size > 0) synchronized(junkFiles) { junkFiles.add(target); totalBytes += size }
                                }
                            }
                        }
                    }
                }
            }

            // ── Phase 4: Walk external storage for target file types (35%→60%) ───
            _uiState.emit(AppUiState.Scanning(0.35f, app.getString(R.string.state_scanning_shredder)))

            val allFiles = mutableListOf<File>()
            var scannedCount = 0
            val promptDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>().also { scanDeferred = it }
            var continueWalk = true

            externalRoot.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    !name.startsWith(".") && !name.equals("Android", ignoreCase = true)
                }
                .forEach { file ->
                    if (!continueWalk) return@forEach
                    if (file.isFile) {
                        scannedCount++
                        // Emit every 200 files; cap walk at 10k without user approval
                        if (scannedCount % 200 == 0) {
                            // 35%→58%, capped at 10k files = 100% of this phase
                            val walkProgress = (scannedCount.toFloat() / 10000f).coerceAtMost(1f)
                            val p = 0.35f + walkProgress * 0.23f
                            _uiState.emit(AppUiState.Scanning(p, app.getString(R.string.state_scanning_shredder)))
                        }
                        if (scannedCount == 10000) {
                            viewModelScope.launch(Dispatchers.Main) { largeScanPromptEvent.emit(Unit) }
                            val continueAll = kotlinx.coroutines.runBlocking { promptDeferred.await() }
                            if (!continueAll) { continueWalk = false; return@forEach }
                        }
                        if (isTargetFileType(file)) allFiles.add(file)
                    }
                }

            // ── Phase 5: Build large files list (60%→65%) ────────────────────────
            _uiState.emit(AppUiState.Scanning(0.60f, "Indexing large files…"))
            val largeFilesList = allFiles
                .filter { it.length() >= 10 * 1024 * 1024 }
                .map { LargeFileItem(file = it, size = it.length(), isSelected = false) }

            // ── Phase 6: Duplicate hashing (65%→90%) ─────────────────────────────
            _uiState.emit(AppUiState.Scanning(0.65f, "Finding duplicates…"))
            val duplicateGroupsList = mutableListOf<DuplicateGroup>()
            val sizeGroups = allFiles.groupBy { it.length() }.filter { it.key > 0 && it.value.size > 1 }
            val sizeGroupList = sizeGroups.entries.toList()
            sizeGroupList.forEachIndexed { groupIdx, (fileSize, filesWithSize) ->
                val md5Groups = filesWithSize.groupBy { getMD5OfFirstMB(it) }
                md5Groups.forEach { (_, duplicatesList) ->
                    if (duplicatesList.size > 1) {
                        val sorted = duplicatesList.sortedBy { it.lastModified() }
                        val dupFiles = sorted.mapIndexed { index, file ->
                            DuplicateFile(file = file, size = fileSize, isSelected = (index > 0))
                        }
                        duplicateGroupsList.add(DuplicateGroup(files = dupFiles, fileSize = fileSize))
                    }
                }
                // Emit every 20 groups
                if (groupIdx % 20 == 0) {
                    val p = 0.65f + (groupIdx.toFloat() / sizeGroupList.size.coerceAtLeast(1)) * 0.25f
                    _uiState.emit(AppUiState.Scanning(p, "Finding duplicates…"))
                }
            }

            // ── Phase 7: Calculate cache folder sizes (90%→99%) ──────────────────
            _uiState.emit(AppUiState.Scanning(0.90f, "Calculating cache sizes…"))
            val cacheItemsList = junkFiles.mapIndexed { idx, file ->
                if (idx % 5 == 0) {
                    val p = 0.90f + (idx.toFloat() / junkFiles.size.coerceAtLeast(1)) * 0.09f
                    _uiState.emit(AppUiState.Scanning(p, "Calculating cache sizes…"))
                }
                CacheItem(file = file, size = calculateFolderSize(file), isSelected = true)
            }

            // ── Phase 8: Done — emit 100% briefly then show results ───────────────
            _uiState.emit(AppUiState.Scanning(1.0f, "Scan complete!"))
            kotlinx.coroutines.delay(400) // Brief pause so user sees 100%
            _uiState.emit(
                AppUiState.ShredderReady(
                    ShredderResult(
                        cacheItems = cacheItemsList,
                        duplicateGroups = duplicateGroupsList,
                        largeFiles = largeFilesList
                    )
                )
            )
        }
    }

    fun performCleanup(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null) {
        val currentState = _uiState.value
        if (currentState !is AppUiState.ShredderReady) return

        viewModelScope.launch(Dispatchers.IO) {
            haptics?.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)

            val result = currentState.result
            ThumbnailCache.clear()

            val filesToDelete = mutableListOf<File>()
            result.cacheItems.filter { it.isSelected }.forEach { filesToDelete.add(it.file) }
            result.duplicateGroups.forEach { group ->
                group.files.forEach { if (it.isSelected) filesToDelete.add(it.file) }
            }
            result.largeFiles.filter { it.isSelected }.forEach { filesToDelete.add(it.file) }

            filesToDelete.forEach { file ->
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                } catch (e: Exception) { /* ignore */ }
            }
            _uiState.emit(AppUiState.CleanFinished)
        }
    }

    private fun calculateFolderSize(directory: File): Long {
        var size: Long = 0
        try {
            directory.walkTopDown().maxDepth(10).forEach { file ->
                if (file.isFile) size += file.length()
            }
        } catch (e: Exception) {}
        return size
    }

    fun backToHome() {
        ThumbnailCache.clear()
        // Always force a fresh reload so storage stats reflect any cleanup that ran.
        loadApps(force = true)
    }

    private fun loadApps(force: Boolean = false, onCompletion: ((Boolean) -> Unit)? = null) {
        _isRefreshing.value = true
        // Skip reload if we already have cached data (e.g. returning from a popup)
        if (!force && cachedSuccessState != null) {
            viewModelScope.launch { 
                _uiState.emit(cachedSuccessState!!) 
                onCompletion?.invoke(false)
                _isRefreshing.value = false
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Save current package names BEFORE the refresh so we can detect removals
            val packagesBefore: Set<String> = if (onCompletion != null) {
                (cachedSuccessState as? AppUiState.Success)?.apps?.map { it.packageName }?.toSet() ?: emptySet()
            } else emptySet()

            _uiState.emit(AppUiState.Loading)
            val context = getApplication<Application>()
            val pm = context.packageManager

            // OPTIMIZATION: Use getInstalledApplications first (lighter Binder payload)
            // then process metadata in smaller chunks to avoid inter-process buffer overflow.
            val apps = try {
                pm.getInstalledApplications(0)
            } catch (e: Exception) {
                emptyList()
            }

            // Usage stats — 30 days is plenty
            val usageMap: Map<String, android.app.usage.UsageStats> = try {
                if (hasUsageAccess()) {
                    val mgr = context.getSystemService(Application.USAGE_STATS_SERVICE) as UsageStatsManager
                    val end = System.currentTimeMillis()
                    mgr.queryAndAggregateUsageStats(end - 30L * 24 * 60 * 60 * 1000, end)
                } else emptyMap()
            } catch (e: Exception) { emptyMap() }

            val userPackages = mutableListOf<android.content.pm.PackageInfo>()
            val systemPackages = mutableListOf<android.content.pm.PackageInfo>()

            // Batch fetch PackageInfo to avoid DeadObjectException / BadParcelableException
            apps.forEach { appInfo ->
                try {
                    val pkg = pm.getPackageInfo(appInfo.packageName, 0)
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                        systemPackages.add(pkg)
                    } else {
                        userPackages.add(pkg)
                    }
                } catch (e: Exception) {
                    // App might have been uninstalled during scan
                }
            }

            // Define mapping logic to not repeat code
            fun mapToAppInfo(pkgInfo: android.content.pm.PackageInfo): AppInfo? {
                val appInfo = pkgInfo.applicationInfo ?: return null
                val pkgName = appInfo.packageName
                val file = File(appInfo.sourceDir)
                val sizeBytes = if (file.exists()) file.length() else 0L

                val installerStore: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val src = pm.getInstallSourceInfo(pkgName)
                        src.initiatingPackageName ?: src.installingPackageName
                    } catch (e: Exception) { null }
                } else {
                    @Suppress("DEPRECATION") pm.getInstallerPackageName(pkgName)
                }

                return AppInfo(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = pkgName,
                    version = pkgInfo.versionName ?: "1.0",
                    installedDate = pkgInfo.firstInstallTime,
                    size = formatSize(sizeBytes),
                    sizeBytes = sizeBytes,
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    targetSdk = appInfo.targetSdkVersion,
                    lastUsed = usageMap[pkgName]?.lastTimeUsed ?: 0L,
                    lastUpdated = pkgInfo.lastUpdateTime,
                    installerStore = installerStore,
                    isSplitApk = !appInfo.splitSourceDirs.isNullOrEmpty()
                )
            }

            // PRE-CALCULATE STORAGE (fast)
            val statPath = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)
                Environment.getExternalStorageDirectory().path
            else
                Environment.getDataDirectory().path
            val stat = StatFs(statPath)
            val bs = stat.blockSizeLong
            val storage = StorageInfo(
                free = formatSize(stat.availableBlocksLong * bs),
                total = formatSize(stat.blockCountLong * bs),
                percentUsed = 1f - (stat.availableBlocksLong.toFloat() / stat.blockCountLong)
            )

            // FAST PASS 1: Construct array of only User Apps (+/- 50 apps on average vs 400). 
            // Send this to UI immediately. Screen loads in 0.2s.
            val userAppList = userPackages.mapNotNull { mapToAppInfo(it) }.sortedBy { it.name.lowercase() }
            if (userAppList.isNotEmpty()) {
                _uiState.emit(AppUiState.Success(userAppList, storage))
            }

            // SLOW PASS 2: Background process all massive pre-installed System ROM apps seamlessly.
            val systemAppList = systemPackages.mapNotNull { mapToAppInfo(it) }.sortedBy { it.name.lowercase() }
            val fullAppList = (userAppList + systemAppList).sortedBy { it.name.lowercase() }

            val finalSuccessState = if (fullAppList.isEmpty()) null else AppUiState.Success(fullAppList, storage)
            cachedSuccessState = finalSuccessState
            _uiState.emit(finalSuccessState ?: AppUiState.Empty)

            if (onCompletion != null) {
                // A package was truly uninstalled if it was present before but is absent now
                val packagesAfter = fullAppList.map { it.packageName }.toSet()
                val somethingWasRemoved = (packagesBefore - packagesAfter).isNotEmpty()
                withContext(Dispatchers.Main) {
                    onCompletion.invoke(somethingWasRemoved)
                }
            }

            // Save name mapping so external uninstalls can be identified on next open
            saveAppNameMapping(fullAppList)

            // Load icons lazily in batches — UI is already visible and interactive
            val iconsDir = File(context.cacheDir, "icons_cache")
            if (!iconsDir.exists()) iconsDir.mkdirs()

            // Check what's already on disk to avoid re-emitting flow for every file
            val existingFiles = iconsDir.listFiles()?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
            _cachedIcons.value = existingFiles

            viewModelScope.launch(Dispatchers.IO) {
                val newCachedPackages = _cachedIcons.value.toMutableSet()
                // Use the userPackages and systemPackages we just filtered
                val allPkgs = (userPackages + systemPackages)
                
                allPkgs.chunked(30).forEach { chunk ->
                    var changed = false
                    chunk.forEach { pkgInfo ->
                        val appInfo = pkgInfo.applicationInfo ?: return@forEach
                        val pkgName = appInfo.packageName
                        val iconFile = File(iconsDir, "$pkgName.png")
                        
                        // If file doesn't exist or is older than the app's latest update, save it
                        if (!iconFile.exists() || iconFile.lastModified() < pkgInfo.lastUpdateTime) {
                            try {
                                val bitmap = pm.getApplicationIcon(appInfo).toBitmap()
                                FileOutputStream(iconFile).use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                }
                                newCachedPackages.add(pkgName)
                                changed = true
                            } catch (e: Exception) { /* skip bad icon */ }
                        } else if (!newCachedPackages.contains(pkgName)) {
                            newCachedPackages.add(pkgName)
                            changed = true
                        }
                    }
                    if (changed) {
                        _cachedIcons.value = newCachedPackages.toSet()
                    }
                }
            }
            _isRefreshing.value = false
        }
    }

    fun extractApk(app: AppInfo, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                haptics?.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                val context = getApplication<Application>()
                val pm = context.packageManager
                val packageInfo = pm.getApplicationInfo(app.packageName, 0)
                val isSplit = packageInfo.splitSourceDirs != null && packageInfo.splitSourceDirs!!.isNotEmpty()

                val destDir = File(_extractionPath.value)
                if (!destDir.exists()) destDir.mkdirs()

                val extension = if (isSplit) "apks" else "apk"
                val destFile = File(destDir, "${app.name.replace(" ", "_")}_v${app.version}.$extension")

                if (isSplit) {
                    java.util.zip.ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                        val baseFile = File(packageInfo.sourceDir)
                        baseFile.inputStream().use { input ->
                            zos.putNextEntry(java.util.zip.ZipEntry("base.apk"))
                            input.copyTo(zos)
                            zos.closeEntry()
                        }
                        packageInfo.splitSourceDirs?.forEach { splitPath ->
                            val splitFile = File(splitPath)
                            splitFile.inputStream().use { input ->
                                zos.putNextEntry(java.util.zip.ZipEntry(splitFile.name))
                                input.copyTo(zos)
                                zos.closeEntry()
                            }
                        }
                    }
                    if (!destFile.exists() || destFile.length() == 0L) {
                        destFile.delete()
                        throw Exception("Split APK extraction failed or resulted in an empty file.")
                    }
                } else {
                    // Standard single APK
                    val srcFile = File(packageInfo.sourceDir)
                    FileInputStream(srcFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    if (destFile.length() != srcFile.length()) {
                        destFile.delete()
                        throw Exception("File size mismatch: Extraction incomplete or corrupt.")
                    }
                }

                // Force Android's Media Scanner to see the new file immediately so it's not marked as corrupt or broken
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    null,
                    null
                )

                withContext(Dispatchers.Main) {
                    // Fire a notification with the full path
                    val channelId = "apk_extraction"
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = android.app.NotificationChannel(
                            channelId,
                            "APK Extraction",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT
                        ).apply { description = "Notifies when an APK backup is complete" }
                        notificationManager.createNotificationChannel(channel)
                    }

                    val canPostNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true

                    if (canPostNotification) {
                        val notifBuilder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(if (isSplit) context.getString(R.string.notif_bundle_complete) else context.getString(R.string.notif_apk_complete))
                            .setContentText(
                                if (isSplit) context.getString(R.string.notif_bundle_summary, app.name)
                                else context.getString(R.string.notif_apk_summary, app.name)
                            )
                            .setStyle(
                                androidx.core.app.NotificationCompat.BigTextStyle()
                                    .bigText(
                                        if (isSplit)
                                            context.getString(R.string.notif_bundle_big, app.name, destFile.absolutePath)
                                        else
                                            context.getString(R.string.notif_apk_big, app.name, destFile.absolutePath)
                                    )
                            )
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)

                        notificationManager.notify(app.packageName.hashCode(), notifBuilder.build())
                    } else {
                        Toast.makeText(context, context.getString(R.string.toast_extracted, destFile.absolutePath), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val appCtx = getApplication<Application>()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, appCtx.getString(R.string.toast_extract_fail, (e.message ?: "Unknown Error")), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

sealed class AppUiState {
    object Loading : AppUiState()
    object Empty : AppUiState()
    data class Success(val apps: List<AppInfo>, val storage: StorageInfo) : AppUiState()
    data class Scanning(val progress: Float, val currentTask: String) : AppUiState()
    data class ShredderReady(val result: ShredderResult) : AppUiState()
    object CleanFinished : AppUiState()
}

data class CacheItem(val file: java.io.File, val size: Long, val isSelected: Boolean = true)
data class DuplicateFile(val file: java.io.File, val size: Long = 0L, val isSelected: Boolean = false)
data class DuplicateGroup(val files: List<DuplicateFile>, val fileSize: Long = 0L)
data class LargeFileItem(val file: java.io.File, val size: Long = 0L, val isSelected: Boolean = false)

data class ShredderResult(
    val cacheItems: List<CacheItem>,
    val duplicateGroups: List<DuplicateGroup>,
    val largeFiles: List<LargeFileItem>
)

data class StorageInfo(
    val free: String,
    val total: String,
    val percentUsed: Float
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val version: String,
    val installedDate: Long,
    val size: String,
    val sizeBytes: Long,
    val isSystem: Boolean,
    val targetSdk: Int,
    val lastUsed: Long,
    val lastUpdated: Long,
    val installerStore: String?,
    val isSplitApk: Boolean = false  // True when Play Store installed as App Bundle (multiple APK splits)
)

data class HistoryAppRecord(
    val name: String,
    val packageName: String,
    val timestamp: Long
)

@Composable
fun PaywallScreen(
    isDarkTheme: Boolean,
    onDismiss: () -> Unit,
    onPurchaseSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity() as? Activity
    var selectedPlanId by rememberSaveable { mutableStateOf(BillingManager.PRODUCT_YEARLY) }
    val isPremium by BillingManager.isPremium.collectAsState()
    val prices by BillingManager.productPrices.collectAsState()
    val isRestoring by BillingManager.isRestoring.collectAsState()
    val restoreResult by BillingManager.restoreResult.collectAsState()
    val pricesLoaded by BillingManager.pricesLoaded.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isPremium) {
        if (isPremium) onPurchaseSuccess()
    }

    // Show snackbar feedback for restore result
    LaunchedEffect(restoreResult) {
        restoreResult?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            BillingManager.clearRestoreResult()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        UninstallerTheme(darkTheme = isDarkTheme) {
        val bgColor = MaterialTheme.colorScheme.background
        val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bgColor
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = Color.Transparent
            ) { scaffoldPadding ->
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        bgColor,
                        surfaceColor.copy(alpha = 0.6f)
                    )
                )
            ).padding(scaffoldPadding)) {
                // Background Glow
                Box(modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer(translationY = -150f)
                    .background(Brush.radialGradient(listOf(LogoPurple.copy(alpha = 0.15f), Color.Transparent)))
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header with Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, null, tint = onSurfaceColor.copy(alpha = 0.5f))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // App Logo + Crown
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = surfaceColor.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.1f))
                        ) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Surface(
                            color = PremiumGold,
                            shape = CircleShape,
                            modifier = Modifier.offset(x = 8.dp, y = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Star, 
                                null, 
                                tint = Color.Black, 
                                modifier = Modifier.padding(4.dp).size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.paywall_title),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = onSurfaceColor
                    )
                    Text(
                        text = stringResource(R.string.paywall_subtitle),
                        fontSize = 14.sp,
                        color = onSurfaceVariantColor
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PaywallFeatureItem(stringResource(R.string.paywall_feature_ads))
                        PaywallFeatureItem(stringResource(R.string.paywall_feature_history))
                        PaywallFeatureItem(stringResource(R.string.paywall_feature_future))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Shows animated shimmer loading state until real localized prices are loaded from Play Billing.
                    val monthlyPriceStr = prices[BillingManager.PRODUCT_MONTHLY]
                    val yearlyPriceStr = prices[BillingManager.PRODUCT_YEARLY]
                    val lifetimePriceStr = prices[BillingManager.PRODUCT_LIFETIME]

                    val isLoading = !pricesLoaded

                    val mPriceNum = monthlyPriceStr?.replace(Regex("[^0-9,.]"), "")?.replace(",", ".")?.toFloatOrNull()
                    val yPriceNum = yearlyPriceStr?.replace(Regex("[^0-9,.]"), "")?.replace(",", ".")?.toFloatOrNull()
                    val savingsPercent = if (mPriceNum != null && yPriceNum != null && mPriceNum > 0f) {
                        Math.round((1f - (yPriceNum / 12f) / mPriceNum) * 100f).coerceAtLeast(0)
                    } else 44
                    val yearlyBadgeText = "SAVE $savingsPercent%"

                    val isSubscribeEnabled = !isLoading && when (selectedPlanId) {
                        BillingManager.PRODUCT_MONTHLY -> monthlyPriceStr != null
                        BillingManager.PRODUCT_YEARLY -> yearlyPriceStr != null
                        BillingManager.PRODUCT_LIFETIME -> lifetimePriceStr != null
                        else -> false
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaywallPlanCard(
                            id = BillingManager.PRODUCT_MONTHLY,
                            name = stringResource(R.string.paywall_plan_monthly),
                            price = monthlyPriceStr ?: "—",
                            period = stringResource(R.string.paywall_per_month),
                            selected = selectedPlanId == BillingManager.PRODUCT_MONTHLY,
                            isLoading = isLoading,
                            onClick = { selectedPlanId = BillingManager.PRODUCT_MONTHLY }
                        )
                        PaywallPlanCard(
                            id = BillingManager.PRODUCT_YEARLY,
                            name = stringResource(R.string.paywall_plan_yearly),
                            price = yearlyPriceStr ?: "—",
                            period = stringResource(R.string.paywall_per_year),
                            badge = yearlyBadgeText,
                            selected = selectedPlanId == BillingManager.PRODUCT_YEARLY,
                            isLoading = isLoading,
                            onClick = { selectedPlanId = BillingManager.PRODUCT_YEARLY }
                        )
                        PaywallPlanCard(
                            id = BillingManager.PRODUCT_LIFETIME,
                            name = stringResource(R.string.paywall_plan_lifetime),
                            price = lifetimePriceStr ?: "—",
                            period = stringResource(R.string.paywall_one_time),
                            badge = stringResource(R.string.paywall_badge_lifetime),
                            selected = selectedPlanId == BillingManager.PRODUCT_LIFETIME,
                            isLoading = isLoading,
                            onClick = { selectedPlanId = BillingManager.PRODUCT_LIFETIME }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { 
                            activity?.let { BillingManager.launchPurchase(it, selectedPlanId) }
                        },
                        enabled = isSubscribeEnabled,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldGreen,
                            disabledContainerColor = EmeraldGreen.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.paywall_action_subscribe), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = EmeraldGreen,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Restoring...",
                                fontSize = 12.sp,
                                color = EmeraldGreen
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.paywall_action_restore),
                                fontSize = 12.sp,
                                color = EmeraldGreen,
                                modifier = Modifier.clickable { BillingManager.restorePurchases() }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(R.string.paywall_footer_note),
                        fontSize = 10.sp,
                        color = onSurfaceVariantColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            }
        }
        }
    }
}

@Composable
fun PaywallFeatureItem(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = EmeraldGreen, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PaywallPlanCard(
    id: String,
    name: String,
    price: String,
    period: String,
    badge: String? = null,
    selected: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (selected && !isLoading) EmeraldGreen.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(
            if (selected && !isLoading) 2.dp else 1.dp,
            if (selected && !isLoading) EmeraldGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    ) {
        val rowModifier = if (isLoading) {
            val transition = rememberInfiniteTransition(label = "shimmer")
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "shimmer_alpha"
            )
            Modifier
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .graphicsLayer { this.alpha = alpha }
        } else {
            Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
        }

        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected && !isLoading,
                onClick = if (isLoading) null else onClick,
                enabled = !isLoading,
                colors = RadioButtonDefaults.colors(selectedColor = EmeraldGreen, unselectedColor = Color.Gray)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (badge != null && !isLoading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (id == BillingManager.PRODUCT_LIFETIME) PremiumGold else EmeraldGreen,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = if (id == BillingManager.PRODUCT_LIFETIME) Color.Black else Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .width(70.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    )
                } else {
                    AnimatedContent(
                        targetState = price,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith
                            fadeOut(animationSpec = tween(150))
                        },
                        label = "price_anim"
                    ) { displayPrice ->
                        Text(
                            text = displayPrice,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(text = period, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallerApp(
    viewModel: AppViewModel,
    ratingManager: RatingManager,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onUninstallRequest: (AppInfo, Boolean) -> Unit,
    onRequestStoragePermission: () -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var prevScreen by rememberSaveable { mutableStateOf("home") }
    var showPaywall by rememberSaveable { mutableStateOf(false) }
    var paywallSource by rememberSaveable { mutableStateOf("") } // "launch", "history", "settings"
    val isPremium by BillingManager.isPremium.collectAsState()
    val currentUiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? MainActivity }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    // ── Launch Paywall Logic ──────────────────────────────────────────────────
    val triggerSignal by viewModel.triggerPaywallSignal.collectAsState()
    
    LaunchedEffect(triggerSignal, isPremium) {
        if (triggerSignal && !isPremium) {
            val prefs = context.getSharedPreferences("surgical_uninstaller_prefs", Context.MODE_PRIVATE)
            val lastShownDate = prefs.getString("paywall_last_shown_date", "") ?: ""
            val lastVersion = prefs.getInt("paywall_last_version", 0)
            val currentVersion = viewModel.appVersionCode
            
            val calendar = java.util.Calendar.getInstance()
            val today = "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.MONTH)}-${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"

            val isFirstInstall = lastShownDate.isEmpty()
            val isUpdate = lastVersion != 0 && lastVersion < currentVersion
            val isNewDay = lastShownDate != today

            if (isFirstInstall || isUpdate || isNewDay) {
                paywallSource = "launch"
                showPaywall = true
                prefs.edit().putInt("paywall_last_version", currentVersion).apply()
            }
            viewModel.consumePaywallTrigger()
        }
    }

    var showLargeScanPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.largeScanPromptEvent.collect {
            showLargeScanPrompt = true
        }
    }

    // ── Update System States ────────────────────────────────────────────────
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("") }
    var isUpdateReady by remember { mutableStateOf(false) }
    var isMandatoryUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val act = context as? MainActivity ?: return@LaunchedEffect
        act.updateManager.checkForUpdates(
            updateLauncher = act.updateLauncher,
            onFlexibleUpdateDownloaded = { isUpdateReady = true },
            onManualUpdateAvailable = { url, isMandatory ->
                updateUrl = url
                isMandatoryUpdate = isMandatory
                showUpdateDialog = true
            }
        )

    }

    // ── Ad triggers on screen transitions ───────────────────────────────────




    // ── Update Available Dialog (Fallback Path) ──────────────────────────────


    val navigateHome = {
        if (currentScreen == "settings") AdManager.onNavigatedHomeFromSettings()
        currentScreen = "home"
    }

    BackHandler(enabled = currentScreen != "home") {
        navigateHome()
    }

    Scaffold(
        snackbarHost = {
            if (isUpdateReady) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { 
                            (context as? MainActivity)?.updateManager?.completeUpdate() 
                        }) {
                            Text("RESTART", color = EmeraldGreen, fontWeight = FontWeight.Black)
                        }
                    },
                    containerColor = Charcoal,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Update downloaded and ready to install!")
                }
            }
        },
        topBar = {
            if (currentUiState !is AppUiState.Scanning && currentUiState !is AppUiState.ShredderReady) {
                UninstallerTopBar(
                    title = when (currentScreen) {
                        "home" -> "UNINSTALLER"
                        "history" -> "HISTORY"
                        else -> "SETTINGS"
                    },
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    onSettingsClick = {
                        if (currentScreen == "settings") navigateHome()
                        else currentScreen = "settings"
                    },
                    showSettings = currentScreen == "home",
                    isPremium = isPremium,
                    // Home: Refresh + History | Settings: Share + History | History: Refresh + Settings
                    onRefresh = when (currentScreen) {
                        "home" -> {
                            {
                                viewModel.refreshList()
                            }
                        }
                        "history" -> {
                            { 
                                viewModel.refreshHistory()
                            }
                        }
                        else -> null
                    },
                    onShareApp = if (currentScreen == "settings") {
                        {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                val contextPackage = context.packageName
                                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, contextPackage))
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.item_share)))
                        }
                    } else null,
                    onSettingsNav = if (currentScreen == "history") { { currentScreen = "settings" } } else null,
                    onHistory = if (currentScreen != "history") {
                        {
                            if (isPremium) {
                                currentScreen = "history"
                            } else {
                                paywallSource = "history"
                                showPaywall = true
                            }
                        }
                    } else null
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = currentUiState) {
                is AppUiState.Loading -> LoadingScreen()
                is AppUiState.Empty -> EmptyStateScreen { viewModel.refreshList() }
                is AppUiState.Scanning -> DeepCleanProgressScreen(state.progress, state.currentTask)
                is AppUiState.ShredderReady -> ShredderScreen(
                    result = state.result,
                    viewModel = viewModel,
                    isPremium = isPremium,
                    onClean = { viewModel.performCleanup(haptics) },
                    onCancel = { viewModel.backToHome() },
                    onShowPaywall = {
                        paywallSource = "shredder"
                        showPaywall = true
                    }
                )
                is AppUiState.CleanFinished -> {
                    val activity = context.findActivity() as? MainActivity
                    // Navigate home immediately, then show the ad after the DONE tap.
                    CleanFinishedScreen {
                        viewModel.backToHome()
                        AdManager.onCleanFinishedDone(onDismiss = {
                            activity?.showActionRatingPrompt()
                        })
                    }
                }
                is AppUiState.Success -> {
                    val sortBy by viewModel.sortBy.collectAsState()
                    val isAscending by viewModel.isAscending.collectAsState()
                    val storageThreshold by viewModel.storageThreshold.collectAsState()
                    val cachedIcons by viewModel.cachedIcons.collectAsState()
                    val isRefreshing by viewModel.isRefreshing.collectAsState()

                    if (currentScreen == "home") {
                        HomeScreen(
                            apps = state.apps,
                            storage = state.storage,
                            cachedIcons = cachedIcons,
                            isRefreshing = isRefreshing,
                            sortBy = sortBy,
                            isAscending = isAscending,
                            storageThreshold = storageThreshold,
                            onSortChange = { s, a -> viewModel.updateSortPreference(s, a) },
                            onUninstallRequest = onUninstallRequest,
                            onDeepCleanStart = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    if (Environment.isExternalStorageManager()) {
                                        viewModel.startDeepClean()
                                    } else {
                                        onRequestStoragePermission()
                                    }
                                } else {
                                    viewModel.startDeepClean()
                                }
                            },
                            onExtract = { 
                                viewModel.extractApk(it, haptics)
                                AdManager.onExtractApkSuccess(onDismiss = {
                                    activity?.showActionRatingPrompt()
                                })
                            },
                            onRefreshButton = { 
                                viewModel.refreshList()
                                AdManager.onHomeRefreshTapped()
                            },
                            onSwipeRefresh = {
                                viewModel.refreshList()
                                AdManager.onSwipeToRefresh()
                            },
                            onBulkUninstall = { apps, doUninstall ->
                                if (AdManager.isRewardedReady()) {
                                    AdManager.showRewarded(
                                        onRewardEarned = {
                                            doUninstall()
                                            activity?.showActionRatingPrompt()
                                        },
                                        onDismiss = {}
                                    )
                                } else {
                                    doUninstall()
                                    activity?.showActionRatingPrompt()
                                }
                            },
                            isBulkAdUnlocked = AdManager.rewardedUnlockActive,
                            onTabClicked = { AdManager.onTabSwitchedByClick() },
                            onTabSwiped = { AdManager.onTabSwitchedBySwipe() },
                            onSelectAll = { AdManager.onSelectAll() },
                            onSelectionChanged = { count -> AdManager.onSelectionChanged(count) },
                            isPremium = isPremium
                        )
                    } else if (currentScreen == "history") {
                        val history by viewModel.uninstalledHistory.collectAsState()
                        val cachedIcons by viewModel.cachedIcons.collectAsState()
                        HistoryScreen(
                            history = history,
                            cachedIcons = cachedIcons,
                            onClearHistory = { viewModel.clearHistory() },
                            onBack = { currentScreen = "home" }
                        )
                    } else {
                        SettingsScreen(
                            viewModel = viewModel,
                            ratingManager = ratingManager,
                            onShowPaywall = {
                                paywallSource = "settings"
                                showPaywall = true
                            }
                        )
                    }
                }
            }
        }

        // ── Overlay Dialogs ───────────────────────────────────────────────────
        if (showUpdateDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                icon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        coil.compose.AsyncImage(
                            model = R.mipmap.ic_launcher_foreground,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Icon(painter = painterResource(id = R.drawable.ic_system_update), null, tint = EmeraldGreen, modifier = Modifier.size(32.dp))
                    }
                },
                title = {
                    Text(
                        if (isMandatoryUpdate) "Critical Update Required!" else "New Update Available!",
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Text(
                        if (isMandatoryUpdate) 
                            "This version has critical improvements. You must update to the latest version to continue using the app."
                        else 
                            "A new version of Uninstaller is ready. Update now to get the latest features and security improvements.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val activity = context as? MainActivity
                            activity?.updateManager?.launchUpdateUrl(activity, updateUrl)
                            showUpdateDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text("UPDATE NOW", fontWeight = FontWeight.Black)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showUpdateDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isMandatoryUpdate) "REMIND ME LATER" else "LATER", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                },
                shape = RoundedCornerShape(28.dp),
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                ),
                modifier = Modifier.padding(24.dp)
            )
        }

        if (showPaywall) {
            PaywallScreen(
                isDarkTheme = isDarkTheme,
                onDismiss = { 
                    showPaywall = false
                    val prefs = context.getSharedPreferences("surgical_uninstaller_prefs", Context.MODE_PRIVATE)
                    val calendar = java.util.Calendar.getInstance()
                    val today = "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.MONTH)}-${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
                    prefs.edit().putString("paywall_last_shown_date", today).apply()
                },
                onPurchaseSuccess = {
                    showPaywall = false
                    val prefs = context.getSharedPreferences("surgical_uninstaller_prefs", Context.MODE_PRIVATE)
                    val calendar = java.util.Calendar.getInstance()
                    val today = "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.MONTH)}-${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
                    prefs.edit().putString("paywall_last_shown_date", today).apply()
                    if (paywallSource == "history") currentScreen = "history"
                }
            )
        }
        if (showLargeScanPrompt) {
            AlertDialog(
                onDismissRequest = {
                    showLargeScanPrompt = false
                    viewModel.resumeScan(false)
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = "Scan Warning", tint = EmeraldGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Large Library Scan", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        "You have a large storage library (>10,000 files). Scanning all files for duplicates and large sizes may take some time. Would you like to continue scanning all files or process the files scanned so far?",
                        color = Color.LightGray
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLargeScanPrompt = false
                            viewModel.resumeScan(true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                    ) {
                        Text("Continue Full Scan", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showLargeScanPrompt = false
                            viewModel.resumeScan(false)
                        }
                    ) {
                        Text("Stop Here", color = Color.Gray)
                    }
                },
                containerColor = Charcoal,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}


@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = EmeraldGreen, 
                strokeWidth = 6.dp, 
                modifier = Modifier.size(64.dp),
                trackColor = LogoPurple.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.state_analyzing), fontWeight = FontWeight.Black, color = LogoPurple)
                Text(stringResource(R.string.state_system), fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
        }
    }
}

@Composable
fun EmptyStateScreen(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(id = R.drawable.ic_search_off), null, Modifier.size(80.dp), tint = EmeraldGreen.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.state_no_apps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = LogoPurple)
                Text(stringResource(R.string.state_found), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text(stringResource(R.string.state_scan_retry), textAlign = TextAlign.Center, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRetry, 
                colors = ButtonDefaults.buttonColors(containerColor = LogoPurple),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.action_scan_again), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallerTopBar(
    title: String,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    showSettings: Boolean,
    onRefresh: (() -> Unit)? = null,
    onShareApp: (() -> Unit)? = null,
    onSettingsNav: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    isPremium: Boolean = false
) {
    @OptIn(ExperimentalMaterial3Api::class)
    CenterAlignedTopAppBar(
        navigationIcon = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((-2).dp),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.content_refresh),
                                tint = EmeraldGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (onHistory != null) {
                        IconButton(onClick = onHistory) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_history),
                                    contentDescription = "History",
                                    tint = if (isPremium) EmeraldGreen else Color.Red,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (!isPremium) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Locked",
                                        tint = Color.Red,
                                        modifier = Modifier.size(10.dp).align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (onShareApp != null) {
                        IconButton(onClick = onShareApp) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.item_share),
                                tint = EmeraldGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (onSettingsNav != null) {
                        IconButton(onClick = onSettingsNav) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = EmeraldGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        title = {
            when (title) {
                "UNINSTALLER" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZEE ", fontSize = 17.sp, fontWeight = FontWeight.Black, color = LogoPurple, letterSpacing = 0.2.sp)
                        Text("UNINSTALLER", fontSize = 17.sp, fontWeight = FontWeight.Black, color = EmeraldGreen, letterSpacing = 0.2.sp)
                    }
                }
                "SETTINGS" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZEE ", fontSize = 17.sp, fontWeight = FontWeight.Black, color = LogoPurple, letterSpacing = 0.2.sp)
                        Text("SETTINGS", fontSize = 17.sp, fontWeight = FontWeight.Black, color = EmeraldGreen, letterSpacing = 0.2.sp)
                    }
                }
                "HISTORY" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZEE ", fontSize = 17.sp, fontWeight = FontWeight.Black, color = LogoPurple, letterSpacing = 0.2.sp)
                        Text("HISTORY", fontSize = 17.sp, fontWeight = FontWeight.Black, color = EmeraldGreen, letterSpacing = 0.2.sp)
                    }
                }
                else -> {
                    Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = LogoPurple, letterSpacing = 1.sp)
                }
            }
        },
        actions = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((-2).dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    IconButton(onClick = onThemeToggle) {
                        Icon(
                            painter = painterResource(id = if (isDarkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                            contentDescription = stringResource(R.string.content_toggle_theme),
                            tint = if (isDarkTheme) EmeraldGreen else LogoPurple,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = if (!showSettings) Icons.Default.Home else Icons.Default.Settings,
                            contentDescription = stringResource(R.string.content_navigate),
                            tint = LogoPurple,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<AppInfo>, 
    storage: StorageInfo,
    cachedIcons: Set<String>,
    isRefreshing: Boolean,
    sortBy: String,
    isAscending: Boolean,
    storageThreshold: Float,
    onSortChange: (String, Boolean) -> Unit,
    onUninstallRequest: (AppInfo, Boolean) -> Unit,
    onDeepCleanStart: () -> Unit,
    onExtract: (AppInfo) -> Unit,
    onRefreshButton: () -> Unit,
    onSwipeRefresh: () -> Unit,
    onBulkUninstall: (List<AppInfo>, () -> Unit) -> Unit = { _, action -> action() },
    isBulkAdUnlocked: Boolean,
    onTabClicked: () -> Unit = {},
    onTabSwiped: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onSelectionChanged: (Int) -> Unit = {},
    isPremium: Boolean = false
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedApps = remember { mutableStateListOf<AppInfo>() }
    val pagerState = rememberPagerState(pageCount = { 2 })
    var previousPage by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val isRewardedReady by AdManager.isRewardedReadyFlow.collectAsState()
    
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != previousPage) {
            onTabSwiped()
            previousPage = pagerState.currentPage
        }
    }
    
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredApps = remember(searchQuery, apps, sortBy, isAscending) {
        val filtered = apps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        val sorted = when (sortBy) {
            "Size" -> filtered.sortedBy { it.sizeBytes }
            "Date" -> filtered.sortedBy { it.installedDate }
            "Date (Used)" -> filtered.sortedBy { it.lastUsed }
            else -> filtered.sortedBy { it.name.lowercase() }
        }
        if (isAscending) sorted else sorted.reversed()
    }

    val userApps = filteredApps.filter { !it.isSystem }
    val systemApps = filteredApps.filter { it.isSystem }

    var appActionToShow by remember { mutableStateOf<AppInfo?>(null) }
    val context = LocalContext.current

    val userListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val systemListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(sortBy, isAscending, searchQuery) {
        userListState.scrollToItem(0)
        systemListState.scrollToItem(0)
    }

    // Compute usage access here so it's in scope for the sort menu
    val hasUsageAccess = (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).let {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            it.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            it.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Reduce horizontal inset so app list rows span closer to screen edges
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            // 1. Unified Search & Sort Row — same height as toggle row (36dp)
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    ),
                    decorationBox = { innerTextField ->
                        Row(
                            Modifier.padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = EmeraldGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) Text(stringResource(R.string.search_hint), fontSize = 14.sp, color = Color.Gray)
                                innerTextField()
                            }
                        }
                    }
                )

                Box(modifier = Modifier.size(36.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(EmeraldGreen.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .clickable { showSortMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_sort), null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        Text(stringResource(R.string.sort_by), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        val sortOptions = listOf(
                            "Name" to stringResource(R.string.sort_name),
                            "Size" to stringResource(R.string.sort_size),
                            "Date" to stringResource(R.string.sort_date),
                            "Date (Used)" to stringResource(R.string.sort_date_used)
                        )
                        sortOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = { if(sortBy == key) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                                onClick = { 
                                    if (key == "Date (Used)" && !hasUsageAccess) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        } catch (e: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        }
                                    } else {
                                        onSortChange(key, isAscending)
                                    }
                                    showSortMenu = false 
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = { Text(if (isAscending) stringResource(R.string.sort_asc) else stringResource(R.string.sort_desc)) },
                            leadingIcon = { Icon(painter = painterResource(id = if (isAscending) R.drawable.ic_vertical_align_top else R.drawable.ic_vertical_align_bottom), null) },
                            onClick = { onSortChange(sortBy, !isAscending); showSortMenu = false }
                        )
                    }
                }
            }

            // 2. Storage Alert & Info Row Merged
            val isCritical = storage.percentUsed >= storageThreshold
            
            Spacer(modifier = Modifier.height(8.dp)) // Reduced for tighter vertical flow
            
            val usedPercent = (storage.percentUsed * 100).toInt()
            val freePercent = 100 - usedPercent

            Surface(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                color = if (isCritical) Color.Red.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
                border = if (isCritical) androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f)) else null
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCritical) {
                            Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isCritical) stringResource(R.string.storage_critical, storage.free, storage.total) else stringResource(R.string.storage_free, storage.free, storage.total),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCritical) Color.Red else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(5.dp))
                        Surface(
                            color = if (isCritical) Color.Red.copy(alpha = 0.15f) else EmeraldGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.storage_free_percent, freePercent),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isCritical) Color.Red else EmeraldGreen
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.apps_count, apps.size),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCritical) Color.Red else EmeraldGreen
                    )
                }
            }

            // 3. Compact Tabs & Select All
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentVisibleApps = if (pagerState.currentPage == 0) userApps else systemApps
                val isAllSelected = selectedApps.isNotEmpty() && currentVisibleApps.isNotEmpty() && selectedApps.size >= currentVisibleApps.size
                
                if (pagerState.currentPage == 0) {
                    Checkbox(
                        checked = isAllSelected,
                        onCheckedChange = { 
                            if (isAllSelected) {
                                selectedApps.clear()
                                onSelectionChanged(0)
                            }
                            else {
                                selectedApps.clear()
                                selectedApps.addAll(currentVisibleApps)
                                onSelectAll() // fire interstitial ad on Select All
                            }
                        },
                        colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen, uncheckedColor = Color.Gray.copy(alpha = 0.5f)),
                        modifier = Modifier.size(32.dp).padding(end = 4.dp)
                    )
                } else {
                    Spacer(Modifier.width(32.dp))
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(2.dp)
                ) {
                    val userCount = if (searchQuery.isEmpty()) apps.count { !it.isSystem } else userApps.size
                    val systemCount = if (searchQuery.isEmpty()) apps.count { it.isSystem } else systemApps.size
                    
                    val userTabStr = stringResource(R.string.tab_user, userCount)
                    val systemTabStr = stringResource(R.string.tab_system, systemCount)

                    listOf(userTabStr, systemTabStr).forEachIndexed { index, title ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) EmeraldGreen else Color.Transparent)
                                .clickable {
                                    onTabClicked()
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Content Pager with Pull-to-Refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { onSwipeRefresh() },
                modifier = Modifier.weight(1f)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { it }
                ) { page ->
                    val pageApps = if (page == 0) userApps else systemApps
                    LazyColumn(
                        state = if (page == 0) userListState else systemListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(pageApps, key = { it.packageName }) { app ->
                            val isSelected = selectedApps.contains(app)
                            AppRow(
                                app = app,
                                isIconCached = cachedIcons.contains(app.packageName),
                                isSelected = isSelected,
                                onToggle = {
                                    if (isSelected) {
                                        selectedApps.remove(app)
                                        if (selectedApps.isEmpty()) onSelectionChanged(0)
                                    } else {
                                        selectedApps.add(app)
                                        onSelectionChanged(selectedApps.size)
                                    }
                                },
                                onMenuClick = { appActionToShow = app }
                            )
                        }
                    }
                }
            }
        }

        // 5. Storage Sweep Floating Button
        if (selectedApps.isEmpty()) {
            // Measure the banner ad height so FAB always clears it on all screen sizes
            var bannerHeightPx by remember { mutableIntStateOf(0) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val bannerHeightDp = with(density) { bannerHeightPx.toDp() }
            // If premium, use standard 16dp. Otherwise, sit exactly at the top of the banner (0dp gap)
            val fabBottomPadding = if (isPremium) 16.dp else bannerHeightDp.coerceAtLeast(16.dp)

            androidx.compose.material3.SmallFloatingActionButton(
                onClick = onDeepCleanStart,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = fabBottomPadding, end = 16.dp),
                containerColor = EmeraldGreen,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painter = painterResource(id = R.drawable.ic_delete_forever), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_surgical_clean), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 6. Banner Ad — always visible at absolute bottom when no bulk bar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        bannerHeightPx = size.height
                    }
            ) {
                BannerAdView()
            }
        }

        // 7. Bulk Action Bar (At the absolute bottom)
        if (selectedApps.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val currentVisibleApps = if (pagerState.currentPage == 0) userApps else systemApps
                BulkActionBar(
                    count = selectedApps.size,
                    reclaimedSpace = formatSize(selectedApps.sumOf { it.sizeBytes }),
                    isAdUnlocked = if (selectedApps.size == 1) !isRewardedReady || isBulkAdUnlocked else !isRewardedReady,
                    onUninstall = {
                        val appsToUninstall = selectedApps.toList()
                        if (appsToUninstall.size == 1) {
                            val action = {
                                onUninstallRequest(appsToUninstall.first(), false)
                                selectedApps.clear()
                                onSelectionChanged(0)
                            }
                            if (AdManager.isRewardedReady() && !isBulkAdUnlocked) {
                                AdManager.showRewarded(onRewardEarned = action, onDismiss = {})
                            } else {
                                // Category 2: Single uninstall from bar
                                onUninstallRequest(appsToUninstall.first(), false)
                            }
                        } else {
                            onBulkUninstall(appsToUninstall) {
                                appsToUninstall.forEach { 
                                    try {
                                        val intent = Intent(Intent.ACTION_DELETE).apply {
                                            data = Uri.parse("package:${it.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {}
                                }
                                selectedApps.clear()
                                onSelectionChanged(0)
                            }
                        }
                    },
                    onSelectAll = {
                        if (selectedApps.size == currentVisibleApps.size) {
                            selectedApps.clear()
                            onSelectionChanged(0)
                        } else {
                            selectedApps.clear()
                            selectedApps.addAll(currentVisibleApps)
                            onSelectAll()
                        }
                    },
                    isAllSelected = selectedApps.size >= currentVisibleApps.size && currentVisibleApps.isNotEmpty()
                )
            }
        }
    }
        
        // App Action Dialog
        appActionToShow?.let { app ->
            AppActionDialog(
                app = app,
                isIconCached = cachedIcons.contains(app.packageName),
                onDismiss = { appActionToShow = null },
                onUninstall = { 
                    // Category 1: Always-On Uninstall
                    onUninstallRequest(app, true)
                    appActionToShow = null
                },
                onLaunch = {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                        intent?.let { context.startActivity(it) }
                    } catch (e: Exception) {}
                    appActionToShow = null
                },
                onDetails = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${app.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                    appActionToShow = null
                },
                onExtract = {
                    onExtract(app)
                    appActionToShow = null
                },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        val contextPackage = context.packageName
                        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_specific_app, app.name, app.packageName, contextPackage))
                    }
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.action_share)))
                    appActionToShow = null
                }
            )
        }
}

@Composable
fun AppRow(app: AppInfo, isIconCached: Boolean, isSelected: Boolean, onToggle: () -> Unit, onMenuClick: () -> Unit) {
    val canUninstall = !app.isSystem
    val context = LocalContext.current
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { if (canUninstall) onToggle() },
                onLongClick = onMenuClick
            ),
        color = if (isSelected) LogoPurple.copy(alpha = 0.12f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.3f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).graphicsLayer(alpha = if (canUninstall) 1.0f else 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Side: Checkbox
            if (canUninstall) {
                Checkbox(
                    checked = isSelected, 
                    onCheckedChange = { onToggle() }, 
                    colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen),
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                Icon(
                    Icons.Default.Lock, 
                    "Protected", 
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), 
                    modifier = Modifier.padding(end = 8.dp).size(20.dp)
                )
            }

            // App icon — shows placeholder until lazy icon loads
            val iconFile = File(context.cacheDir, "icons_cache/${app.packageName}.png")
            
            if (isIconCached && iconFile.exists()) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(iconFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_android), null,
                        tint = EmeraldGreen.copy(alpha = 0.4f),
                        modifier = Modifier.size(26.dp))
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.version, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(" • ${app.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                }
            }

            // Right Side: 3 Dots Menu
            IconButton(onClick = onMenuClick, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AppActionDialog(
    app: AppInfo,
    isIconCached: Boolean,
    onDismiss: () -> Unit, 
    onUninstall: () -> Unit, 
    onLaunch: () -> Unit, 
    onDetails: () -> Unit,
    onExtract: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = Color.Gray, fontWeight = FontWeight.Black) }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val context = LocalContext.current
                val iconFile = File(context.cacheDir, "icons_cache/${app.packageName}.png")
                
                Surface(
                    shape = CircleShape, 
                    border = androidx.compose.foundation.BorderStroke(2.dp, EmeraldGreen.copy(alpha = 0.5f)),
                    shadowElevation = 4.dp
                ) {
                    if (isIconCached && iconFile.exists()) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(context)
                                .data(iconFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).padding(6.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).padding(6.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(painter = painterResource(id = R.drawable.ic_android), null,
                                tint = EmeraldGreen.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = LogoPurple.copy(alpha = 0.6f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Surgical Action Buttons Row 1: UNINSTALL
                if (!app.isSystem) {
                    Button(
                        onClick = onUninstall,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_uninstall), fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                
                // 2. Split APK warning banner
                if (app.isSplitApk) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFF8C00).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8C00).copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, tint = Color(0xFFFF8C00), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.bundle_warning),
                                fontSize = 10.sp,
                                color = Color(0xFFFF8C00),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 3. Surgical Action Buttons Row 2: LAUNCH & EXTRACT/BACKUP
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_launch), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_launch), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onExtract,
                        modifier = Modifier.weight(1.2f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (app.isSplitApk) Color(0xFFFF8C00).copy(alpha = 0.1f) else LogoPurple.copy(alpha = 0.1f),
                            contentColor = if (app.isSplitApk) Color(0xFFFF8C00) else LogoPurple
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (app.isSplitApk) Color(0xFFFF8C00).copy(alpha = 0.3f) else LogoPurple.copy(alpha = 0.2f)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(painter = painterResource(id = if (app.isSplitApk) R.drawable.ic_folder_zip else R.drawable.ic_system_update), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (app.isSplitApk) stringResource(R.string.action_extract_apks) else stringResource(R.string.action_extract_apk),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // 4. Row 3: SHARE & SETTINGS
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onDetails,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))

                // 5. Info Grid
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                val installDate = sdf.format(java.util.Date(app.installedDate))
                val updateDate = sdf.format(java.util.Date(app.lastUpdated))
                val neverStr = stringResource(R.string.info_never)
                val lastUsedDate = if (app.lastUsed == 0L) neverStr else sdf.format(java.util.Date(app.lastUsed))
                val source = when {
                    app.installerStore == null -> stringResource(R.string.source_unknown)
                    app.installerStore.contains("vending") || app.installerStore.contains("play") -> stringResource(R.string.source_play)
                    app.installerStore.contains("amazon") -> stringResource(R.string.source_amazon)
                    else -> app.installerStore
                }
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_version), app.version, Icons.Default.Info)
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_size), app.size, painterResource(id = R.drawable.ic_sd_storage))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_target), "API ${app.targetSdk}", Icons.Default.Info)
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_last_used), lastUsedDate, painterResource(id = R.drawable.ic_history))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_installed), installDate, Icons.Default.Info)
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_updated), updateDate, Icons.Default.Refresh)
                }
                Spacer(Modifier.height(6.dp))
                InfoBox(Modifier.fillMaxWidth(), stringResource(R.string.info_source), source, Icons.AutoMirrored.Filled.List)
            }
        },
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )
}

@Composable
fun InfoBox(modifier: Modifier, label: String, value: String, icon: ImageVector) {
    InfoBoxInternal(modifier = modifier, label = label, value = value, iconContent = {
        Icon(icon, null, Modifier.size(12.dp), tint = LogoPurple)
    })
}

@Composable
fun InfoBox(modifier: Modifier, label: String, value: String, painter: androidx.compose.ui.graphics.painter.Painter) {
    InfoBoxInternal(modifier = modifier, label = label, value = value, iconContent = {
        Icon(painter, null, Modifier.size(12.dp), tint = LogoPurple)
    })
}

@Composable
private fun InfoBoxInternal(modifier: Modifier, label: String, value: String, iconContent: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                iconContent()
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Gray)
            }
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ActionMenuItem(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun BulkActionBar(count: Int, reclaimedSpace: String, isAdUnlocked: Boolean, onUninstall: () -> Unit, onSelectAll: () -> Unit, isAllSelected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 16.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isAdUnlocked) LogoPurple.copy(alpha = 0.2f) else EmeraldGreen.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAllSelected, onCheckedChange = { onSelectAll() }, colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen))
                    Text(text = stringResource(R.string.items_selected_count, count), fontWeight = FontWeight.ExtraBold, color = LogoPurple)
                }
                Text(
                    text = stringResource(R.string.reclaiming_space, reclaimedSpace),
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            
            val containerColor = if (isAdUnlocked) LogoPurple else EmeraldGreen
            Button(
                onClick = onUninstall,
                colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (!isAdUnlocked) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Watch Ad\n& Uninstall", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 14.sp)
                } else {
                    Text(stringResource(R.string.action_uninstall), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DeepCleanProgressScreen(progress: Float, currentTask: String) {
    // Animate progress smoothly between emitted values so the circle never jumps
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "scanProgress"
    )
    val displayPercent = (animatedProgress * 100).toInt()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text("STORAGE ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = LogoPurple)
                Text("SWEEP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text(currentTask, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))

            Spacer(modifier = Modifier.height(64.dp))

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(200.dp),
                    color = EmeraldGreen,
                    strokeWidth = 12.dp,
                    trackColor = LogoPurple.copy(alpha = 0.1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$displayPercent%",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        color = LogoPurple
                    )
                    Text("COMPLETE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen, letterSpacing = 2.sp)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = EmeraldGreen,
                trackColor = LogoPurple.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Banner ad anchored to absolute bottom (Matching Home layout)
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            BannerAdView()
        }
    }
}

object ThumbnailCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    fun get(key: String): Any? = cache[key]
    fun put(key: String, value: Any) {
        if (cache.size < 500) cache[key] = value
    }
    fun clear() = cache.clear()
}

val eyeIcon: ImageVector
    get() = ImageVector.Builder(
        name = "eye",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = SolidColor(Color.White),
        strokeLineWidth = 0f
    ) {
        moveTo(12f, 4.5f)
        curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
        curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
        curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
        curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
        close()
        moveTo(12f, 17f)
        curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
        curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
        curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
        curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
        close()
        moveTo(12f, 9f)
        curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
        curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
        curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
        curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
        close()
    }.build()

private fun getMimeType(file: java.io.File): String {
    val extension = file.extension.lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}

fun openFile(context: android.content.Context, file: java.io.File) {
    try {
        val authority = "${context.packageName}.provider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        val mimeType = context.contentResolver.getType(uri) ?: getMimeType(file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open file with"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No application found to open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VideoThumbnail(file: java.io.File, modifier: Modifier = Modifier) {
    val cached = remember(file) { ThumbnailCache.get(file.absolutePath) as? android.graphics.Bitmap }
    var thumbnail by remember(file) { mutableStateOf(cached) }

    if (thumbnail == null) {
        LaunchedEffect(file) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(128, 128), null)
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                } catch (e: java.lang.Exception) {
                    null
                }
            }?.let { bitmap ->
                ThumbnailCache.put(file.absolutePath, bitmap)
                thumbnail = bitmap
            }
        }
    }

    if (thumbnail != null) {
        androidx.compose.foundation.Image(
            bitmap = thumbnail!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Icon(Icons.Default.PlayArrow, null, tint = EmeraldGreen)
    }
}

@Composable
fun ApkThumbnail(file: java.io.File, modifier: Modifier = Modifier) {
    val cached = remember(file) { ThumbnailCache.get(file.absolutePath) as? android.graphics.drawable.Drawable }
    var iconDrawable by remember(file) { mutableStateOf(cached) }
    val context = LocalContext.current

    if (iconDrawable == null) {
        LaunchedEffect(file) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
                    val appInfo = info?.applicationInfo
                    if (appInfo != null) {
                        appInfo.sourceDir = file.absolutePath
                        appInfo.publicSourceDir = file.absolutePath
                        appInfo.loadIcon(pm)
                    } else null
                } catch (e: java.lang.Exception) {
                    null
                }
            }?.let { drawable ->
                ThumbnailCache.put(file.absolutePath, drawable)
                iconDrawable = drawable
            }
        }
    }

    if (iconDrawable != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(iconDrawable)
                }
            },
            modifier = modifier.fillMaxSize()
        )
    } else {
        Icon(Icons.Default.Settings, null, tint = EmeraldGreen)
    }
}

@Composable
fun AudioThumbnail(file: java.io.File, modifier: Modifier = Modifier) {
    val cached = remember(file) { ThumbnailCache.get(file.absolutePath) as? android.graphics.Bitmap }
    var artwork by remember(file) { mutableStateOf(cached) }

    if (artwork == null) {
        LaunchedEffect(file) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val art = retriever.embeddedPicture
                    retriever.release()
                    if (art != null) {
                        android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                    } else null
                } catch (e: java.lang.Exception) {
                    null
                }
            }?.let { bitmap ->
                ThumbnailCache.put(file.absolutePath, bitmap)
                artwork = bitmap
            }
        }
    }

    if (artwork != null) {
        androidx.compose.foundation.Image(
            bitmap = artwork!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Icon(Icons.Default.PlayArrow, null, tint = EmeraldGreen)
    }
}

@Composable
fun FileThumbnail(file: java.io.File, modifier: Modifier = Modifier) {
    val extension = file.extension.lowercase()
    when {
        extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> {
            AsyncImage(
                model = file,
                contentDescription = null,
                modifier = modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        extension in setOf("mp4", "mkv", "avi", "3gp", "webm", "mov") -> {
            VideoThumbnail(file = file, modifier = modifier)
        }
        extension == "apk" -> {
            ApkThumbnail(file = file, modifier = modifier)
        }
        extension in setOf("mp3", "m4a", "wav", "ogg", "flac") -> {
            AudioThumbnail(file = file, modifier = modifier)
        }
        else -> {
            Icon(Icons.Default.Info, null, tint = EmeraldGreen)
        }
    }
}

@Composable
fun ShimmerBadge(text: String) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFD700),
            Color(0xFFFFF8DC),
            Color(0xFFFFD700)
        ),
        start = androidx.compose.ui.geometry.Offset(translateAnim - 200f, 0f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, 200f)
    )

    Box(
        modifier = Modifier
            .background(brush, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun LockedFeatureOverlay(onUnlockClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = PremiumGold,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Premium Feature",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Unlock the File Shredder to scan and permanently delete duplicate or large files.",
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onUnlockClick,
                colors = ButtonDefaults.buttonColors(containerColor = PremiumGold),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "UNLOCK NOW",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun CacheTabContent(
    items: List<CacheItem>,
    onToggle: (java.io.File) -> Unit,
    onSelectAll: (Boolean) -> Unit
) {
    // Wrap in remember to avoid recomputing on every recomposition unrelated to items
    val allSelected by remember(items) { derivedStateOf { items.isNotEmpty() && items.all { it.isSelected } } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Select Cache Folders", color = Color.Gray, fontSize = 14.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectAll(!allSelected) }
            ) {
                Text(
                    text = if (allSelected) "Deselect All" else "Select All",
                    color = EmeraldGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onSelectAll(it) },
                    colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                )
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No cache files found", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Key prevents full list rebuild on selection change
                items(items, key = { it.file.absolutePath }) { item ->
                    val size = item.size
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Charcoal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(item.file) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.file.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.file.absolutePath,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formatSize(size),
                                    color = EmeraldGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Checkbox(
                                checked = item.isSelected,
                                onCheckedChange = { onToggle(item.file) },
                                colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicatesTabContent(
    groups: List<DuplicateGroup>,
    onToggle: (java.io.File) -> Unit,
    onPreviewImage: (java.io.File) -> Unit,
    onSelectAll: (Boolean) -> Unit
) {
    // Wrap in remember+derivedStateOf to avoid recomputing on every recomposition
    val allSelected by remember(groups) {
        derivedStateOf {
            groups.isNotEmpty() && groups.all { group ->
                var groupAllSelected = true
                for (i in 1 until group.files.size) {
                    if (!group.files[i].isSelected) {
                        groupAllSelected = false
                        break
                    }
                }
                groupAllSelected
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Select Duplicates (Oldest Kept)", color = Color.Gray, fontSize = 14.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectAll(!allSelected) }
            ) {
                Text(
                    text = if (allSelected) "Deselect All" else "Select All",
                    color = EmeraldGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onSelectAll(it) },
                    colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                )
            }
        }

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No duplicate files found", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    item(key = "header_$groupIndex") {
                        Text(
                            text = "Group ${groupIndex + 1} (Size: ${formatSize(group.fileSize)})",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(group.files, key = { it.file.absolutePath }) { item ->
                        val isImage = item.file.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
                        val isVideo = item.file.extension.lowercase() in setOf("mp4", "mkv", "avi", "3gp", "webm", "mov")
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Charcoal),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(item.file) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val context = LocalContext.current
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.3f))
                                        .clickable {
                                            if (isImage) {
                                                onPreviewImage(item.file)
                                            } else {
                                                openFile(context, item.file)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        FileThumbnail(file = item.file)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                .padding(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = eyeIcon,
                                                contentDescription = "Preview Available",
                                                tint = Color.White,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.file.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.file.parentFile?.name ?: "",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val isOldest = group.files.firstOrNull()?.file == item.file
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.file.lastModified())),
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                        if (isOldest) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(EmeraldGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text("KEEP", color = EmeraldGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                Checkbox(
                                    checked = item.isSelected,
                                    onCheckedChange = { onToggle(item.file) },
                                    colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LargeFilesTabContent(
    items: List<LargeFileItem>,
    currentThreshold: Long,
    onThresholdChange: (Long) -> Unit,
    onToggle: (java.io.File) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onPreviewImage: (java.io.File) -> Unit
) {
    var sortBy by remember { mutableStateOf("Size") }
    var isAscending by remember { mutableStateOf(false) }

    // Use pre-cached .size instead of file.length() to avoid disk I/O on the main thread
    val visibleItems = remember(items, currentThreshold, sortBy, isAscending) {
        val filtered = items.filter { it.size >= currentThreshold }
        when (sortBy) {
            "Size" -> if (isAscending) filtered.sortedBy { it.size } else filtered.sortedByDescending { it.size }
            "Date" -> if (isAscending) filtered.sortedBy { it.file.lastModified() } else filtered.sortedByDescending { it.file.lastModified() }
            else -> if (isAscending) filtered.sortedBy { it.file.name.lowercase() } else filtered.sortedByDescending { it.file.name.lowercase() }
        }
    }
    // Wrap in remember+derivedStateOf to avoid recomputing on every recomposition
    val allSelected by remember(visibleItems) { derivedStateOf { visibleItems.isNotEmpty() && visibleItems.all { it.isSelected } } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var expanded by remember { mutableStateOf(false) }
            val thresholds = listOf(
                10L to ">= 10MB",
                20L to ">= 20MB",
                30L to ">= 30MB",
                40L to ">= 40MB",
                50L to ">= 50MB",
                100L to ">= 100MB"
            )
            val currentLabel = thresholds.firstOrNull { it.first * 1024 * 1024 == currentThreshold }?.second ?: ">= 50MB"

            Box {
                Row(
                    modifier = Modifier
                        .clickable { expanded = true }
                        .background(Charcoal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(currentLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Charcoal)
                ) {
                    thresholds.forEach { (mb, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = Color.White) },
                            onClick = {
                                onThresholdChange(mb * 1024 * 1024)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            var sortMenuExpanded by remember { mutableStateOf(false) }
            val sortOptions = listOf("Size", "Date", "Name")
            val sortLabel = when (sortBy) {
                "Size" -> if (isAscending) "Size ↑" else "Size ↓"
                "Date" -> if (isAscending) "Oldest" else "Newest"
                else -> if (isAscending) "Name A-Z" else "Name Z-A"
            }

            Box {
                Row(
                    modifier = Modifier
                        .clickable { sortMenuExpanded = true }
                        .background(Charcoal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(sortLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                    modifier = Modifier.background(Charcoal)
                ) {
                    sortOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, color = Color.White) },
                            onClick = {
                                if (sortBy == option) {
                                    isAscending = !isAscending
                                } else {
                                    sortBy = option
                                    isAscending = if (option == "Name") true else false
                                }
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectAll(!allSelected) }
            ) {
                Text(
                    text = if (allSelected) "Deselect All" else "Select All",
                    color = EmeraldGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onSelectAll(it) },
                    colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                )
            }
        }

        if (visibleItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No large files found", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleItems, key = { it.file.absolutePath }) { item ->
                    val isImage = item.file.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
                    val isVideo = item.file.extension.lowercase() in setOf("mp4", "mkv", "avi", "3gp", "webm", "mov")

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Charcoal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(item.file) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val context = LocalContext.current
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.3f))
                                    .clickable {
                                        if (isImage) {
                                            onPreviewImage(item.file)
                                        } else {
                                            openFile(context, item.file)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    FileThumbnail(file = item.file)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            .padding(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = eyeIcon,
                                            contentDescription = "Preview Available",
                                            tint = Color.White,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.file.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.file.absolutePath,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    // Use pre-cached size instead of file.length() disk I/O
                                    text = formatSize(item.size),
                                    color = EmeraldGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Checkbox(
                                checked = item.isSelected,
                                onCheckedChange = { onToggle(item.file) },
                                colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreviewDialog(file: java.io.File, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 5f)
                offset += offsetChange
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
fun ShredConfirmDialog(
    cacheBytes: Long,
    dupsBytes: Long,
    largeBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.Red)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm Permanent Shred", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You are about to permanently shred the selected files. This action CANNOT be undone. The files will be completely deleted.",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Breakdown:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (cacheBytes > 0) {
                    Text("• Cache Folders: ${formatSize(cacheBytes)}", color = Color.LightGray, fontSize = 13.sp)
                }
                if (dupsBytes > 0) {
                    Text("• Duplicate Files: ${formatSize(dupsBytes)}", color = Color.LightGray, fontSize = 13.sp)
                }
                if (largeBytes > 0) {
                    Text("• Large Files: ${formatSize(largeBytes)}", color = Color.LightGray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("SHRED FOREVER", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.Gray)
            }
        },
        containerColor = Charcoal,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ShredderScreen(
    result: ShredderResult,
    viewModel: AppViewModel,
    isPremium: Boolean,
    onClean: () -> Unit,
    onCancel: () -> Unit,
    onShowPaywall: () -> Unit
) {
    val dupsFree by FeatureFlags.shredderDuplicatesFree.collectAsState()
    val largeFree by FeatureFlags.shredderLargeFilesFree.collectAsState()
    val largeFileThreshold by viewModel.largeFileThreshold.collectAsState()

    val isDupsLocked = !isPremium && !dupsFree
    val isLargeLocked = !isPremium && !largeFree

    var selectedTab by remember { mutableStateOf(0) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    // Use remember + derivedStateOf to avoid recomputing sizes on every recomposition.
    // Use pre-cached .size to avoid disk I/O on the main thread.
    val selectedCacheSize by remember(result.cacheItems) {
        derivedStateOf { result.cacheItems.filter { it.isSelected }.sumOf { it.size } }
    }
    val selectedDupsSize by remember(result.duplicateGroups) {
        derivedStateOf { result.duplicateGroups.sumOf { group -> group.files.filter { it.isSelected }.sumOf { it.size } } }
    }
    val selectedLargeSize by remember(result.largeFiles, largeFileThreshold) {
        derivedStateOf { result.largeFiles.filter { it.size >= largeFileThreshold && it.isSelected }.sumOf { it.size } }
    }
    val totalSelectedSize by remember(selectedCacheSize, selectedDupsSize, selectedLargeSize) {
        derivedStateOf { selectedCacheSize + selectedDupsSize + selectedLargeSize }
    }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "File Shredder",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                val showFreeBadge = !isPremium && (dupsFree || largeFree)
                if (showFreeBadge) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ShimmerBadge("LIMITED TIME FREE")
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Charcoal,
                contentColor = Color.White,
                indicator = { tabPositions ->
                    with(TabRowDefaults) {
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = EmeraldGreen
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        // Pre-compute tab label sizes once per result change using remember
                        val cacheTotal = remember(result.cacheItems) { result.cacheItems.sumOf { it.size } }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Cache", fontWeight = FontWeight.Bold)
                            Text(formatSize(cacheTotal), fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                )

                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        // Use pre-cached fileSize from DuplicateGroup to avoid disk I/O
                        val dupsTotal = remember(result.duplicateGroups) {
                            result.duplicateGroups.sumOf { group -> group.fileSize * group.files.size }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Duplicates", fontWeight = FontWeight.Bold)
                                if (isDupsLocked) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Lock, contentDescription = "Locked", tint = PremiumGold, modifier = Modifier.size(12.dp))
                                }
                            }
                            Text(formatSize(dupsTotal), fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                )

                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        // Use pre-cached size to avoid disk I/O
                        val largeTotal = remember(result.largeFiles, largeFileThreshold) {
                            result.largeFiles.filter { it.size >= largeFileThreshold }.sumOf { it.size }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Large Files", fontWeight = FontWeight.Bold)
                                if (isLargeLocked) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.Lock, contentDescription = "Locked", tint = PremiumGold, modifier = Modifier.size(12.dp))
                                }
                            }
                            Text(formatSize(largeTotal), fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> {
                        CacheTabContent(
                            items = result.cacheItems,
                            onToggle = { viewModel.toggleCacheItem(it) },
                            onSelectAll = { select -> viewModel.selectAllCache(select) }
                        )
                    }
                    1 -> {
                        if (isDupsLocked) {
                            LockedFeatureOverlay(onUnlockClick = onShowPaywall)
                        } else {
                            DuplicatesTabContent(
                                groups = result.duplicateGroups,
                                onToggle = { viewModel.toggleDuplicateFile(it) },
                                onPreviewImage = { previewFile = it },
                                  onSelectAll = { select -> viewModel.selectAllDuplicates(select) }
                            )
                        }
                    }
                    2 -> {
                        if (isLargeLocked) {
                            LockedFeatureOverlay(onUnlockClick = onShowPaywall)
                        } else {
                            LargeFilesTabContent(
                                items = result.largeFiles,
                                currentThreshold = largeFileThreshold,
                                onThresholdChange = { viewModel.setLargeFileThreshold(it) },
                                onToggle = { viewModel.toggleLargeFile(it) },
                                onSelectAll = { select -> viewModel.selectAllLargeFiles(select) },
                                onPreviewImage = { previewFile = it }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = {
                        if (totalSelectedSize > 0) {
                            showConfirmDialog = true
                        }
                    },
                    enabled = totalSelectedSize > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldGreen,
                        disabledContainerColor = EmeraldGreen.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "SHRED ${formatSize(totalSelectedSize)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("DISCARD", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                BannerAdView()
            }
        }

        previewFile?.let { file ->
            ImagePreviewDialog(file = file, onDismiss = { previewFile = null })
        }

        if (showConfirmDialog) {
            ShredConfirmDialog(
                cacheBytes = selectedCacheSize,
                dupsBytes = selectedDupsSize,
                largeBytes = selectedLargeSize,
                onConfirm = {
                    showConfirmDialog = false
                    onClean()
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }
}

private fun calculateFolderSize(directory: java.io.File): Long {
    var size: Long = 0
    try {
        directory.walkTopDown().maxDepth(10).forEach { file ->
            if (file.isFile) size += file.length()
        }
    } catch (e: Exception) {}
    return size
}

// ─── Reusable Banner Ad Composable ──────────────────────────────────────────
@Composable
fun BannerAdView() {
    val isPremium by BillingManager.isPremium.collectAsState()
    if (isPremium) return

    var isAdLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Use MaterialTheme colorScheme so banner label follows the app's theme toggle
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    
    // Use centralized AdManager to create and manage banners
    val adView = remember { 
        AdManager.createAdaptiveBanner(
            context,
            onLoaded = { isAdLoaded = true },
            onFailed = { isAdLoaded = false }
        ) 
    } ?: return

    if (!isAdLoaded) {
        // Return 0-height box while loading to keep button at bottom
        Box(modifier = Modifier.height(0.dp).fillMaxWidth())
        return
    }

    DisposableEffect(adView) {
        onDispose { AdManager.destroyBanner(adView) }
    }

    // Render the native AdView with a clear, visible "Advertisement" label
    // above it so creatives cannot be mistaken for system UI or dialogs.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            factory = { _ -> adView }
        )
    }
}

@Composable
fun CleanFinishedScreen(onDone: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? MainActivity }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            // ── LANDSCAPE COMPACT LAYOUT ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Left Column: Icon
                Column(
                    modifier = Modifier.weight(0.35f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = CircleShape, color = EmeraldGreen.copy(alpha = 0.1f), modifier = Modifier.size(80.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.padding(16.dp), tint = LogoPurple)
                    }
                }
                // Right Column: Text + Button
                Column(
                    modifier = Modifier.weight(0.65f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row {
                        Text(stringResource(R.string.title_surgery), fontSize = 24.sp, fontWeight = FontWeight.Black, color = LogoPurple)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.status_complete), fontSize = 24.sp, fontWeight = FontWeight.Black, color = EmeraldGreen)
                    }
                    Text(stringResource(R.string.device_optimized), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDone, 
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LogoPurple),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.action_done), fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            }
        } else {
            // ── PORTRAIT LAYOUT (Existing) ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = CircleShape, color = EmeraldGreen.copy(alpha = 0.1f), modifier = Modifier.size(140.dp)) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.padding(28.dp), tint = LogoPurple)
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row {
                    Text(stringResource(R.string.title_surgery), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = LogoPurple)
                    Text(stringResource(R.string.status_complete), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = EmeraldGreen)
                }
                Text(stringResource(R.string.device_optimized), textAlign = TextAlign.Center, color = Color.Gray, fontWeight = FontWeight.Medium)
                
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onDone, 
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LogoPurple),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Text(stringResource(R.string.action_done), fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
                }
            }

            // Banner ad anchored to absolute bottom (Portrait only)
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                BannerAdView()
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: AppViewModel, 
    ratingManager: RatingManager,
    onShowPaywall: () -> Unit
) {
    val isPremium by BillingManager.isPremium.collectAsState()
    val context = LocalContext.current
    val scanOnLaunch by viewModel.scanOnLaunch.collectAsState()
    val storageThreshold by viewModel.storageThreshold.collectAsState()

    // Permission state — re-checked on every resume so granting in system settings reflects immediately
    fun checkNotifAccess() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true

    var hasUsageAccess by remember { mutableStateOf(viewModel.hasUsageAccess()) }
    var hasFileAccess by remember { mutableStateOf(viewModel.hasAllFilesAccess()) }
    var hasNotificationAccess by remember { mutableStateOf(checkNotifAccess()) }

    // Re-check permissions whenever the user returns to the app (e.g. from system settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = viewModel.hasUsageAccess()
                hasFileAccess = viewModel.hasAllFilesAccess()
                hasNotificationAccess = checkNotifAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.2f))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        // Dummy Spacer to keep circle centered
                        Spacer(modifier = Modifier.width(60.dp)) 

                        // The Circle
                        Surface(
                            shape = CircleShape, 
                            color = Color.White.copy(alpha = 0.05f), 
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                coil.compose.AsyncImage(
                                    model = R.mipmap.ic_launcher_foreground,
                                    contentDescription = null,
                                    modifier = Modifier.align(Alignment.Center).requiredSize(116.dp)
                                )
                            }
                        }
                        
                        // Floating Badge on Right
                        Surface(
                            modifier = Modifier.offset(x = (-8).dp), // Slight overlap for appeal
                            color = if (isPremium) PremiumGold else EmeraldGreen.copy(alpha = 0.1f),
                            shape = CircleShape,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isPremium) {
                                    Icon(Icons.Default.Star, null, tint = Color.Black, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = if (isPremium) stringResource(R.string.status_pro) else stringResource(R.string.status_free),
                                    color = if (isPremium) Color.Black else EmeraldGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                    Row {
                        Text("ZEE ", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = LogoPurple)
                        Text("UNINSTALLER", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = EmeraldGreen)
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.subtitle_precision), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("${stringResource(R.string.info_version)} ${viewModel.appVersionName} • Zee Tech", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        if (!isPremium) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowPaywall() },
                    color = PremiumCardBg,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, PremiumGold.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = PremiumGold.copy(alpha = 0.15f)
                        ) {
                            Icon(
                                Icons.Default.Star, 
                                null, 
                                tint = PremiumGold, 
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_premium_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                            Text(stringResource(R.string.settings_premium_desc), fontSize = 12.sp, color = Color.Gray)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, 
                            null, 
                            tint = Color.Gray, 
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.group_permission_hub)) {
                PermissionHubItem(
                    label = stringResource(R.string.item_usage_intelligence),
                    desc = stringResource(R.string.desc_usage_intelligence),
                    isGranted = hasUsageAccess,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                PermissionHubItem(
                    label = stringResource(R.string.item_surgical_storage),
                    desc = stringResource(R.string.desc_surgical_storage),
                    isGranted = hasFileAccess,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                val notifHubLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    // Mark that we have asked at least once (to detect permanent denial later)
                    context.getSharedPreferences("surgical_uninstaller_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("notif_perm_asked", true).apply()
                }
                PermissionHubItem(
                    label = stringResource(R.string.item_push_notif),
                    desc = stringResource(R.string.desc_push_notif),
                    isGranted = hasNotificationAccess,
                    onClick = {
                        // Helper: open app notification settings (always works, even when permanently denied)
                        fun openNotifSettings() {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }

                        if (!hasNotificationAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val hasAsked = context.getSharedPreferences("surgical_uninstaller_prefs", android.content.Context.MODE_PRIVATE)
                                .getBoolean("notif_perm_asked", false)
                            val canShowRationale = (context as? android.app.Activity)
                                ?.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                                ?: false

                            when {
                                // Never asked yet → show the permission dialog
                                !hasAsked -> notifHubLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                // Denied once (not permanent) → show dialog again
                                canShowRationale -> notifHubLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                // Permanently denied → must go to system settings
                                else -> openNotifSettings()
                            }
                        } else {
                            // Granted or pre-Android 13 → open notification channel settings
                            fun openNotifSettings2() {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                            openNotifSettings2()
                        }
                    }
                )
            }
        }

        item {
            SettingsGroup(stringResource(R.string.group_automation)) {
                SettingsToggle(
                    label = stringResource(R.string.item_deep_scan),
                    checked = scanOnLaunch,
                    onCheckedChange = { viewModel.toggleScanOnLaunch(it) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                // APK extraction path — folder picker + manual edit fallback
                val extractionPath by viewModel.extractionPath.collectAsState()
                var pathEditValue by remember(extractionPath) { mutableStateOf(extractionPath) }
                var isEditing by remember { mutableStateOf(false) }

                // Folder picker launcher
                val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                        val parts = docId.split(":")
                        val realPath = when {
                            parts[0].equals("primary", ignoreCase = true) ->
                                "${Environment.getExternalStorageDirectory().absolutePath}/${parts.getOrElse(1) { "" }}"
                            parts[0].equals("home", ignoreCase = true) ->
                                "${Environment.getExternalStorageDirectory().absolutePath}/Documents/${parts.getOrElse(1) { "" }}"
                            else ->
                                "/storage/${parts[0]}/${parts.getOrElse(1) { "" }}"
                        }.trimEnd('/')
                        pathEditValue = realPath
                        viewModel.updateExtractionPath(realPath)
                        isEditing = false
                    }
                }

                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painter = painterResource(id = R.drawable.ic_folder), null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.item_apk_path), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = extractionPath,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            fontSize = 11.sp,
                            color = EmeraldGreen.copy(alpha = 0.85f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.group_storage_intel)) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.item_alert_threshold), fontWeight = FontWeight.Medium)
                        Text("${(storageThreshold * 100).toInt()}%", color = EmeraldGreen, fontWeight = FontWeight.Black)
                    }
                    Slider(
                        value = storageThreshold,
                        onValueChange = { viewModel.updateStorageThreshold(it) },
                        valueRange = 0.5f..0.99f,
                        colors = SliderDefaults.colors(
                            thumbColor = EmeraldGreen,
                            activeTrackColor = EmeraldGreen,
                            inactiveTrackColor = EmeraldGreen.copy(alpha = 0.2f)
                        )
                    )
                    Text(
                        stringResource(R.string.desc_alert_threshold),
                        fontSize = 10.sp,
                        color = Color.Gray,
                        fontStyle = FontStyle.Italic
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                // Storage Alert Notifications — belongs here next to the threshold setting
                val storageAlertsEnabled by viewModel.storageAlertsEnabled.collectAsState()
                val notifToggleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    // Mark asked; if granted enable alert, if denied open notification settings
                    context.getSharedPreferences("surgical_uninstaller_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("notif_perm_asked", true).apply()
                    if (granted) {
                        viewModel.toggleStorageAlerts(true)
                    } else {
                        // Dialog was shown but user denied → open settings so they know where to enable it
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                }
                SettingsToggle(
                    label = stringResource(R.string.item_storage_alerts),
                    checked = storageAlertsEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val isGranted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!isGranted) {
                                val hasAsked = context.getSharedPreferences("surgical_uninstaller_prefs", android.content.Context.MODE_PRIVATE)
                                    .getBoolean("notif_perm_asked", false)
                                val canShowRationale = (context as? android.app.Activity)
                                    ?.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                                    ?: false
                                when {
                                    // Never asked or denied-once → show dialog
                                    !hasAsked || canShowRationale ->
                                        notifToggleLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    // Permanently denied → open settings
                                    else -> {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                                return@SettingsToggle
                            }
                        }
                        viewModel.toggleStorageAlerts(enabled)
                    }
                )
            }
        }

        item { 
            SettingsGroup(stringResource(R.string.group_support)) {
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = stringResource(R.string.item_more_apps),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/developer?id=ZeeTech+Solutions+Pvt.+Ltd.&hl=en"))
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                SettingsItem(
                    icon = Icons.Default.Star,
                    label = stringResource(R.string.item_rate),
                    onClick = {
                        val activity = context.findActivity() as? Activity ?: return@SettingsItem
                        ratingManager.launchStore(activity, forceStore = true)
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                SettingsItem(
                    icon = Icons.Default.Email,
                    label = stringResource(R.string.item_feedback),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:muzeeali8@gmail.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Zee Uninstaller App Feedback")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                SettingsItem(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.item_share),
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            val contextPackage = context.packageName
                            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, contextPackage))
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.item_share)))
                    }
                )
            }
        }

        item {
            SettingsGroup(stringResource(R.string.group_legal)) {
                SettingsItem(
                    painter = painterResource(id = R.drawable.ic_privacy_tip),
                    label = stringResource(R.string.item_privacy),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zeetechsolutionspvt.blogspot.com/2026/02/uninstaller.html"))
                        context.startActivity(intent)
                    }
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Banner Ad at absolute bottom
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
    ) {
        BannerAdView()
    }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(text = title, fontWeight = FontWeight.Bold, color = EmeraldGreen, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp), fontSize = 14.sp)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    SettingsItemInternal(label = label, onClick = onClick, iconContent = {
        Icon(icon, null, tint = EmeraldGreen, modifier = Modifier.size(22.dp))
    })
}

@Composable
fun SettingsItem(painter: androidx.compose.ui.graphics.painter.Painter, label: String, onClick: () -> Unit) {
    SettingsItemInternal(label = label, onClick = onClick, iconContent = {
        Icon(painter, null, tint = EmeraldGreen, modifier = Modifier.size(22.dp))
    })
}

@Composable
private fun SettingsItemInternal(label: String, onClick: () -> Unit, iconContent: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        iconContent()
        Spacer(Modifier.width(16.dp))
        Text(text = label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
    }
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked, 
            onCheckedChange = onCheckedChange, 
            colors = SwitchDefaults.colors(
                checkedThumbColor = EmeraldGreen,
                checkedTrackColor = EmeraldGreen.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = Color.Gray
            )
        )
    }
}

@Composable
fun PermissionHubItem(label: String, desc: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(desc, fontSize = 11.sp, color = Color.Gray)
        }
        Surface(
            color = if (isGranted) EmeraldGreen.copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f),
            shape = CircleShape
        ) {
            Text(
                text = if (isGranted) stringResource(R.string.permission_allowed) else stringResource(R.string.permission_allow),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                color = if (isGranted) EmeraldGreen else Color.Red
            )
        }
    }
}
@Composable
fun HistoryScreen(
    history: List<HistoryAppRecord>,
    cachedIcons: Set<String>,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = {
                Icon(painter = painterResource(id = R.drawable.ic_delete_forever), null, tint = Color.Red, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(stringResource(R.string.dialog_clear_history_title), fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            },
            text = {
                Text(
                    stringResource(R.string.dialog_clear_history_msg),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.action_clear_all), fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = Color.Gray, fontWeight = FontWeight.Black)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painter = painterResource(id = R.drawable.ic_history), null, modifier = Modifier.size(64.dp), tint = EmeraldGreen.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.history_empty), color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showConfirmDialog = true }) {
                            Text(stringResource(R.string.action_clear_history), color = Color.Red, fontSize = 12.sp)
                        }
                    }
                }
                items(history) { record ->
                    HistoryItem(
                        record = record,
                        isIconCached = cachedIcons.contains(record.packageName)
                    ) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${record.packageName}"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${record.packageName}"))
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }
        
        // Banner Ad at absolute bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            BannerAdView()
        }
    }
}

@Composable
fun HistoryItem(record: HistoryAppRecord, isIconCached: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isIconCached) {
                val iconFile = File(context.cacheDir, "icons_cache/${record.packageName}.png")
                AsyncImage(
                    model = iconFile,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp).background(EmeraldGreen.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painter = painterResource(id = R.drawable.ic_shop), null, tint = EmeraldGreen, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(record.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(record.packageName, fontSize = 11.sp, color = Color.Gray)
            }
            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp), tint = EmeraldGreen)
        }
    }
}

@Composable
fun RatingAvailableDialog(
    onDismiss: () -> Unit,
    onRateNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(EmeraldGreen.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = EmeraldGreen
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.rating_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                coil.compose.AsyncImage(
                    model = R.mipmap.ic_launcher_foreground,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.rating_message),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                   modifier = Modifier.fillMaxWidth(),
                   horizontalArrangement = Arrangement.Center
                ) {
                    repeat(5) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = EmeraldGreen
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRateNow,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.item_rate).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_later), color = Color.White.copy(alpha = 0.5f))
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(24.dp).widthIn(max = 320.dp)
    )
}
