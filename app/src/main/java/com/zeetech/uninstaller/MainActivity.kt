package com.zeetech.uninstaller

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
import android.provider.Settings
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zeetech.uninstaller.ui.theme.UninstallerTheme
import com.zeetech.uninstaller.ui.theme.EmeraldGreen
import com.zeetech.uninstaller.ui.theme.LogoPurple
import com.zeetech.uninstaller.ui.theme.Charcoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: AppViewModel

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        
        // Automated Surgical Scan on Launch
        if (viewModel.scanOnLaunch.value && viewModel.hasAllFilesAccess()) {
            viewModel.startDeepClean()
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
                        isDarkTheme = darkTheme,
                        onThemeToggle = { darkTheme = !darkTheme },
                        onUninstallRequest = { packageName ->
                            val intent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:$packageName")
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
            }
        }
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val uiState: StateFlow<AppUiState> = _uiState

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

    private var discoveredJunk = listOf<File>()

    init {
        loadApps()
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

    fun refreshList() {
        loadApps()
    }

    fun startDeepClean() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.emit(AppUiState.Scanning(0f, "Analyzing system buffers..."))
            
            val junkFiles = mutableListOf<File>()
            var totalBytes = 0L
            val pm = getApplication<Application>().packageManager
            val installedPackageNames = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            val externalRoot = Environment.getExternalStorageDirectory()

            // 1. Root-Level "App Shadow" Folders (Optimized)
            // Fast scan of the root for leftovers
            externalRoot.listFiles()?.filter { it.isDirectory }?.forEach { file ->
                val name = file.name.lowercase()
                if (name.startsWith("com.") || name.startsWith("org.") || name.startsWith("net.") || name.startsWith(".")) {
                    if (!installedPackageNames.contains(name) && !listOf(".android", ".thumbnails").contains(name)) {
                        val size = calculateFolderSize(file)
                        if (size > 0) {
                            synchronized(junkFiles) { junkFiles.add(file); totalBytes += size }
                        }
                    }
                }
            }

            // 2. High-Yield Junk Targets (Direct Jump)
            val globalJunkPaths = listOf(
                "DCIM/.thumbnails", "Pictures/.thumbnails", ".thumbnails", "LOST.DIR", 
                "WhatsApp/Media/.Statuses", "Telegram/Telegram Images/cache", "Telegram/Telegram Video/cache",
                "Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "MIUI/debug_log", "Download/.tmp"
            )
            globalJunkPaths.forEach { path ->
                val target = File(externalRoot, path)
                if (target.exists()) {
                    val size = calculateFolderSize(target)
                    if (size > 0) {
                        synchronized(junkFiles) { junkFiles.add(target); totalBytes += size }
                    }
                }
            }

            // 3. Android Data Clean (Streamlined for Speed)
            val dataRoots = listOf("Android/data", "Android/obb")
            dataRoots.forEach { rootPath ->
                val rootDir = File(externalRoot, rootPath)
                if (rootDir.exists()) {
                    val apps = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    val totalApps = apps.size
                    
                    apps.forEachIndexed { index, appDir ->
                        if (index % 30 == 0) {
                            _uiState.emit(AppUiState.Scanning(0.4f + (index.toFloat()/totalApps.coerceAtLeast(1))*0.5f, "Surgical Scan..."))
                        }
                        
                        if (!installedPackageNames.contains(appDir.name)) {
                            val size = calculateFolderSize(appDir)
                            if (size > 0) synchronized(junkFiles) { junkFiles.add(appDir); totalBytes += size }
                        } else {
                            // FAST targeted cache scan
                            val commonCacheFolders = listOf("cache", ".cache", "code_cache")
                            commonCacheFolders.forEach { cName ->
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

            _uiState.emit(AppUiState.CleanSummary(formatSize(totalBytes), junkFiles.size))
            discoveredJunk = junkFiles
        }
    }

    fun performCleanup(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            haptics?.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            var spaceReclaimed = 0L
            val total = discoveredJunk.size
            discoveredJunk.forEachIndexed { index, file ->
                _uiState.emit(AppUiState.Scanning(index.toFloat() / total, "Surgical Removal: ${file.name}..."))
                try {
                    val size = if (file.isDirectory) calculateFolderSize(file) else file.length()
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    spaceReclaimed += size
                } catch (e: Exception) { /* Ignore deletion errors */ }
            }
            
            _uiState.emit(AppUiState.CleanFinished)
            discoveredJunk = emptyList()
        }
    }

    private fun calculateFolderSize(directory: File): Long {
        var size: Long = 0
        try {
            directory.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile) size += file.length()
            }
        } catch (e: Exception) {}
        return size
    }

    fun backToHome() {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.emit(AppUiState.Loading)
            val context = getApplication<Application>()
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            // Usage intelligence for "Last Used"
            val usageStatsManager = context.getSystemService(Application.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (1000L * 60 * 60 * 24 * 365) // 1 year history
            val usageMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            
            val appList = packages.map { appInfo ->
                val name = pm.getApplicationLabel(appInfo).toString()
                val pkgName = appInfo.packageName
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon = pm.getApplicationIcon(appInfo)
                
                val file = File(appInfo.sourceDir)
                val sizeInBytes = if (file.exists()) file.length() else 0L
                val size = formatSize(sizeInBytes)

                val pkgInfo = try { pm.getPackageInfo(pkgName, 0) } catch(e:Exception){ null }
                val version = pkgInfo?.versionName ?: "1.0"
                val installedDate = pkgInfo?.firstInstallTime ?: 0L
                val lastUsed = usageMap[pkgName]?.lastTimeUsed ?: 0L

                AppInfo(
                    name = name,
                    packageName = pkgName,
                    version = version,
                    installedDate = installedDate,
                    size = size,
                    sizeBytes = sizeInBytes,
                    icon = icon,
                    isSystem = isSystem,
                    targetSdk = appInfo.targetSdkVersion,
                    lastUsed = lastUsed,
                    lastUpdated = pkgInfo?.lastUpdateTime ?: 0L,
                    installerStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            pm.getInstallSourceInfo(pkgName).initiatingPackageName ?: pm.getInstallSourceInfo(pkgName).installingPackageName
                        } catch (e: Exception) { null }
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkgName)
                    }
                )
            }.sortedBy { it.name.lowercase() }

            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            val storage = StorageInfo(
                free = formatSize(availableBlocks * blockSize),
                total = formatSize(totalBlocks * blockSize),
                percentUsed = 1f - (availableBlocks.toFloat() / totalBlocks)
            )

            _uiState.emit(if (appList.isEmpty()) AppUiState.Empty else AppUiState.Success(appList, storage))
        }
    }

    fun extractApk(app: AppInfo, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                haptics?.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                val context = getApplication<Application>()
                val pm = context.packageManager
                val packageInfo = pm.getApplicationInfo(app.packageName, 0)
                val srcFile = File(packageInfo.sourceDir)

                val destDir = File(_extractionPath.value)
                if (!destDir.exists()) destDir.mkdirs()

                val destFile = File(destDir, "${app.name.replace(" ", "_")}_v${app.version}.apk")

                FileInputStream(srcFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

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
                        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("✅ APK Extraction Complete")
                            .setContentText("${app.name} saved to ${destFile.absolutePath}")
                            .setStyle(
                                androidx.core.app.NotificationCompat.BigTextStyle()
                                    .bigText("${app.name} backed up successfully.\n\nSaved to:\n${destFile.absolutePath}")
                            )
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .build()
                        notificationManager.notify(app.packageName.hashCode(), notification)
                    } else {
                        Toast.makeText(context, "Saved: ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Extraction failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
    data class CleanSummary(val spaceFound: String, val itemsCount: Int) : AppUiState()
    object CleanFinished : AppUiState()
}

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
    val icon: Drawable,
    val isSystem: Boolean,
    val targetSdk: Int,
    val lastUsed: Long,
    val lastUpdated: Long,
    val installerStore: String?
)

@Composable
fun UninstallerApp(
    viewModel: AppViewModel,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onUninstallRequest: (String) -> Unit,
    onRequestStoragePermission: () -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    val currentUiState = viewModel.uiState.collectAsState().value
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    BackHandler(enabled = currentScreen != "home") {
        currentScreen = "home"
    }

    Scaffold(
        topBar = {
            if (currentUiState !is AppUiState.Scanning && currentUiState !is AppUiState.CleanSummary) {
                UninstallerTopBar(
                    title = if (currentScreen == "home") "UNINSTALLER" else "SETTINGS",
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    onSettingsClick = { 
                        currentScreen = if (currentScreen == "home") "settings" else "home" 
                    },
                    showSettings = currentScreen == "home",
                    onRefresh = if (currentScreen == "home") {{ viewModel.refreshList() }} else null
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = currentUiState) {
                is AppUiState.Loading -> LoadingScreen()
                is AppUiState.Empty -> EmptyStateScreen { viewModel.refreshList() }
                is AppUiState.Scanning -> DeepCleanProgressScreen(state.progress, state.currentTask)
                is AppUiState.CleanSummary -> CleanupSummaryScreen(
                    space = state.spaceFound, 
                    itemsCount = state.itemsCount,
                    onClean = { viewModel.performCleanup(haptics) },
                    onCancel = { viewModel.backToHome() }
                )
                is AppUiState.CleanFinished -> CleanFinishedScreen { viewModel.backToHome() }
                is AppUiState.Success -> {
                    val sortBy by viewModel.sortBy.collectAsState()
                    val isAscending by viewModel.isAscending.collectAsState()
                    val storageThreshold by viewModel.storageThreshold.collectAsState()

                    if (currentScreen == "home") {
                        HomeScreen(
                            apps = state.apps,
                            storage = state.storage,
                            isRefreshing = currentUiState is AppUiState.Loading && state.apps.isNotEmpty(),
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
                            onRefresh = { viewModel.refreshList() },
                            onExtract = { viewModel.extractApk(it, haptics) }
                        )
                    } else {
                        SettingsScreen(viewModel)
                    }
                }
            }
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
            Row {
                Text("ANALYZING ", fontWeight = FontWeight.Black, color = LogoPurple)
                Text("SYSTEM...", fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
        }
    }
}

@Composable
fun EmptyStateScreen(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.SearchOff, null, Modifier.size(80.dp), tint = EmeraldGreen.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                Text("NO APPS ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = LogoPurple)
                Text("FOUND", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text("Try scanning again or check permissions.", textAlign = TextAlign.Center, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRetry, 
                colors = ButtonDefaults.buttonColors(containerColor = LogoPurple),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("SCAN AGAIN", color = Color.White, fontWeight = FontWeight.Bold)
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
    onRefresh: (() -> Unit)? = null
) {
    CenterAlignedTopAppBar(
        navigationIcon = {
            if (onRefresh != null) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = EmeraldGreen
                    )
                }
            }
        },
        title = {
            when (title) {
                "UNINSTALLER" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "UNINSTALLE",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = LogoPurple,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "R",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = EmeraldGreen,
                            letterSpacing = 1.sp
                        )
                    }
                }
                "SETTINGS" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SETTING",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = LogoPurple,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "S",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = EmeraldGreen,
                            letterSpacing = 1.sp
                        )
                    }
                }
                else -> {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LogoPurple,
                        letterSpacing = 1.sp
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle Theme",
                    tint = if (isDarkTheme) Color.White else Charcoal
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = if (!showSettings) Icons.Default.Home else Icons.Default.Settings,
                    contentDescription = "Navigate",
                    tint = LogoPurple
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    apps: List<AppInfo>, 
    storage: StorageInfo, 
    isRefreshing: Boolean,
    sortBy: String,
    isAscending: Boolean,
    storageThreshold: Float,
    onSortChange: (String, Boolean) -> Unit,
    onUninstallRequest: (String) -> Unit, 
    onDeepCleanStart: () -> Unit,
    onRefresh: () -> Unit,
    onExtract: (AppInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedApps = remember { mutableStateListOf<AppInfo>() }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 1. Unified Search & Sort Row — same height as toggle row (36dp)
            Spacer(modifier = Modifier.height(12.dp))
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
                                if (searchQuery.isEmpty()) Text("Search...", fontSize = 14.sp, color = Color.Gray)
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
                        Icon(Icons.Default.Sort, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        Text("Sort By", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        listOf("Name", "Size", "Date", "Date (Used)").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                trailingIcon = { if(sortBy == option) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                                onClick = { 
                                    if (option == "Date (Used)" && !hasUsageAccess) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        } catch (e: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        }
                                    } else {
                                        onSortChange(option, isAscending)
                                    }
                                    showSortMenu = false 
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = { Text(if (isAscending) "Ascending" else "Descending") },
                            leadingIcon = { Icon(if (isAscending) Icons.Default.VerticalAlignTop else Icons.Default.VerticalAlignBottom, null) },
                            onClick = { onSortChange(sortBy, !isAscending); showSortMenu = false }
                        )
                    }
                }
            }

            // 2. Storage Alert & Info Row
            val isCritical = storage.percentUsed >= storageThreshold
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isCritical) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = Color.Red.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.2f))
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("STORAGE CRITICAL ", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Red)
                        Surface(color = Color.Red.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "${(storage.percentUsed * 100).toInt()}% USED",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Red
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("— Reclaim space now", fontSize = 10.sp, color = Color.Red.copy(alpha = 0.7f))
                    }
                }
            }

            val usedPercent = (storage.percentUsed * 100).toInt()
            val freePercent = 100 - usedPercent

            Surface(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                color = if (isCritical) Color.Red.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Free: ${storage.free} / ${storage.total}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCritical) Color.Red else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(5.dp))
                        Surface(
                            color = if (isCritical) Color.Red.copy(alpha = 0.12f) else EmeraldGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "$freePercent% free",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isCritical) Color.Red else EmeraldGreen
                            )
                        }
                    }
                    Text(
                        text = "Apps: ${apps.size}",
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
                            if (isAllSelected) selectedApps.clear()
                            else {
                                selectedApps.clear()
                                selectedApps.addAll(currentVisibleApps)
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
                    
                    listOf("User ($userCount)", "System ($systemCount)").forEachIndexed { index, title ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) EmeraldGreen else Color.Transparent)
                                .clickable { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
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
                onRefresh = { onRefresh() },
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
                                isSelected = isSelected,
                                onToggle = { if (isSelected) selectedApps.remove(app) else selectedApps.add(app) },
                                onMenuClick = { appActionToShow = app }
                            )
                        }
                    }
                }
            }
        }

        // 5. Surgical Clean Floating Button
        if (selectedApps.isEmpty()) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = onDeepCleanStart,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                containerColor = EmeraldGreen,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoDelete, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Surgical Clean", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // 6. Bulk Action Bar (At the absolute bottom)
        if (selectedApps.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val currentVisibleApps = if (pagerState.currentPage == 0) userApps else systemApps
                BulkActionBar(
                    count = selectedApps.size,
                    reclaimedSpace = formatSize(selectedApps.sumOf { it.sizeBytes }),
                    onUninstall = { selectedApps.forEach { onUninstallRequest(it.packageName) } },
                    onSelectAll = {
                        if (selectedApps.size == currentVisibleApps.size) selectedApps.clear()
                        else {
                            selectedApps.clear()
                            selectedApps.addAll(currentVisibleApps)
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
                onDismiss = { appActionToShow = null },
                onUninstall = { 
                    onUninstallRequest(app.packageName)
                    appActionToShow = null
                },
                onLaunch = {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                        context.startActivity(intent)
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
                        putExtra(Intent.EXTRA_TEXT, "Quickly uninstalling ${app.name}? Check out this Surgical Uninstaller: https://play.google.com/store/apps/details?id=${app.packageName}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share app link"))
                    appActionToShow = null
                }
            )
        }
}

@Composable
fun AppRow(app: AppInfo, isSelected: Boolean, onToggle: () -> Unit, onMenuClick: () -> Unit) {
    val canUninstall = !app.isSystem
    
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
            modifier = Modifier.padding(12.dp).graphicsLayer(alpha = if (canUninstall) 1.0f else 0.5f),
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
                    modifier = Modifier.size(20.dp).padding(end = 8.dp)
                )
            }

            val painter = remember(app.icon) { BitmapPainter(app.icon.toBitmap().asImageBitmap()) }
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            )
            
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
            TextButton(onClick = onDismiss) { Text("CLOSE", color = Color.Gray, fontWeight = FontWeight.Black) }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val painter = remember(app.icon) { BitmapPainter(app.icon.toBitmap().asImageBitmap()) }
                Surface(
                    shape = CircleShape, 
                    border = androidx.compose.foundation.BorderStroke(2.dp, EmeraldGreen.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp
                ) {
                    Image(painter, null, Modifier.size(72.dp).padding(8.dp).clip(CircleShape))
                }
                Spacer(Modifier.height(16.dp))
                Text(app.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = LogoPurple.copy(alpha = 0.6f), textAlign = TextAlign.Center)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // Info Grid
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                val installDate = sdf.format(java.util.Date(app.installedDate))
                val updateDate = sdf.format(java.util.Date(app.lastUpdated))
                val lastUsedDate = if (app.lastUsed == 0L) "Never" else sdf.format(java.util.Date(app.lastUsed))
                val source = when {
                    app.installerStore == null -> "Sideloaded / Unknown"
                    app.installerStore.contains("vending") || app.installerStore.contains("play") -> "Google Play"
                    app.installerStore.contains("amazon") -> "Amazon Store"
                    else -> app.installerStore
                }
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBox(Modifier.weight(1f), "VERSION", app.version, Icons.Default.Info)
                    InfoBox(Modifier.weight(1f), "SIZE", app.size, Icons.Default.SdStorage)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBox(Modifier.weight(1f), "TARGET", "API ${app.targetSdk}", Icons.Default.DataObject)
                    InfoBox(Modifier.weight(1f), "LAST USED", lastUsedDate, Icons.Default.History)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBox(Modifier.weight(1f), "INSTALLED", installDate, Icons.Default.Event)
                    InfoBox(Modifier.weight(1f), "UPDATED", updateDate, Icons.Default.Update)
                }
                Spacer(Modifier.height(8.dp))
                InfoBox(Modifier.fillMaxWidth(), "INSTALLER SOURCE", source, Icons.Default.Source)
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Surgical Action Buttons Row 1: UNINSTALL
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
                        Text("UNINSTALL", fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                
                // Surgical Action Buttons Row 2: LAUNCH & EXTRACT
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Launch, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("LAUNCH", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onExtract,
                        modifier = Modifier.weight(1.2f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LogoPurple.copy(alpha = 0.1f), contentColor = LogoPurple),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LogoPurple.copy(alpha = 0.2f)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("EXTRACT APK", fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // Row 3: SHARE & SETTINGS
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SHARE", fontSize = 11.sp, fontWeight = FontWeight.Black)
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
            }
        },
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun InfoBox(modifier: Modifier, label: String, value: String, icon: ImageVector) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = LogoPurple)
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
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
fun BulkActionBar(count: Int, reclaimedSpace: String, onUninstall: () -> Unit, onSelectAll: () -> Unit, isAllSelected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 16.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, LogoPurple.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAllSelected, onCheckedChange = { onSelectAll() }, colors = CheckboxDefaults.colors(checkedColor = EmeraldGreen))
                    Text(text = "$count Items Selected", fontWeight = FontWeight.ExtraBold, color = LogoPurple)
                }
                Text(
                    text = "Reclaiming $reclaimedSpace",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            
            Button(
                onClick = onUninstall,
                colors = ButtonDefaults.buttonColors(containerColor = LogoPurple, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text("Uninstall", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DeepCleanProgressScreen(progress: Float, currentTask: String) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text("SURGICAL ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = LogoPurple)
                Text("CLEAN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text(currentTask, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(200.dp),
                    color = EmeraldGreen,
                    strokeWidth = 12.dp,
                    trackColor = LogoPurple.copy(alpha = 0.1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(progress * 100).toInt()}%", fontSize = 48.sp, fontWeight = FontWeight.Black, color = LogoPurple)
                    Text("COMPLETE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen, letterSpacing = 2.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = EmeraldGreen,
                trackColor = LogoPurple.copy(alpha = 0.1f)
            )
        }
    }
}

@Composable
fun CleanupSummaryScreen(space: String, itemsCount: Int, onClean: () -> Unit, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = LogoPurple.copy(alpha = 0.1f), modifier = Modifier.size(120.dp)) {
                Icon(Icons.Default.Analytics, null, Modifier.padding(24.dp), tint = EmeraldGreen)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Row {
                Text("ANALYSIS ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = LogoPurple)
                Text("RESULT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text("Surgical cleanup ready to reclaim", color = Color.Gray)
            Text(space, fontSize = 56.sp, fontWeight = FontWeight.Black, color = LogoPurple)
            Text("from $itemsCount cached zones", style = MaterialTheme.typography.bodyMedium, color = EmeraldGreen, fontWeight = FontWeight.Bold)
            
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onClean, 
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Text("CLEAN NOW", fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onCancel) {
                Text("DISCARD", color = Color.Gray.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CleanFinishedScreen(onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = EmeraldGreen.copy(alpha = 0.1f), modifier = Modifier.size(140.dp)) {
                Icon(Icons.Default.Verified, null, Modifier.padding(28.dp), tint = LogoPurple)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Row {
                Text("SURGERY ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = LogoPurple)
                Text("COMPLETE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = EmeraldGreen)
            }
            Text("Your device is now optimized.", textAlign = TextAlign.Center, color = Color.Gray, fontWeight = FontWeight.Medium)
            
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onDone, 
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LogoPurple),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Text("DONE", fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(bottom = 16.dp)) {
                        Image(painterResource(id = R.drawable.uninstaller), null, Modifier.size(80.dp).padding(8.dp))
                    }
                    Row {
                        Text("UNINSTALLE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = LogoPurple)
                        Text("R", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = EmeraldGreen)
                    }
                    Text("Surgical precision for your apps", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = EmeraldGreen.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.width(100.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Version 1.1.0 • Zee Tech", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        item {
            SettingsGroup("Permission Hub") {
                PermissionHubItem(
                    label = "Usage Intelligence",
                    desc = "Enables 'Last Used' tracking for apps.",
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
                    label = "Surgical Storage Access",
                    desc = "Required for Junk & APK Extraction.",
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
                    label = "Push Notifications",
                    desc = "Storage alerts & APK extraction notifications.",
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
            SettingsGroup("Surgical Automation") {
                SettingsToggle(
                    label = "Deep Scan on Launch",
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
                            Icon(Icons.Default.FolderOpen, null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("APK Backup Path", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = EmeraldGreen.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable { folderPickerLauncher.launch(null) }
                            ) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, null, tint = EmeraldGreen, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("BROWSE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = EmeraldGreen)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = LogoPurple.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable {
                                    if (isEditing) viewModel.updateExtractionPath(pathEditValue.trim())
                                    isEditing = !isEditing
                                }
                            ) {
                                Text(
                                    if (isEditing) "SAVE" else "EDIT",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isEditing) EmeraldGreen else LogoPurple
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (isEditing) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = pathEditValue,
                            onValueChange = { pathEditValue = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            singleLine = false,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                    } else {
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
        }

        item {
            SettingsGroup("Storage Intelligence") {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Alert Threshold", fontWeight = FontWeight.Medium)
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
                        "Triggers a visual alert when your system storage exceeds this limit.",
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
                    label = "Storage Alert Notifications",
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
            SettingsGroup("Support & Feedback") {
                SettingsItem(
                    icon = Icons.Default.Apps,
                    label = "More Apps",
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
                    label = "Rate This App",
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/console/u/0/developers/8381881514749219665/app/4976725838031557021/main-store-listing"))
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                SettingsItem(
                    icon = Icons.Default.Email,
                    label = "Send Feedback",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:muzeeali8@gmail.com")
                            putExtra(Intent.EXTRA_SUBJECT, "Uninstaller App Feedback")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
            }
        }

        item {
            SettingsGroup("Legal") {
                SettingsItem(
                    icon = Icons.Default.PrivacyTip,
                    label = "Privacy Policy",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zeetechsolutionspvt.blogspot.com/2026/02/uninstaller.html"))
                        context.startActivity(intent)
                    }
                )
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = EmeraldGreen, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
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
                text = if (isGranted) "GRANTED" else "PENDING",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                color = if (isGranted) EmeraldGreen else Color.Red
            )
        }
    }
}
