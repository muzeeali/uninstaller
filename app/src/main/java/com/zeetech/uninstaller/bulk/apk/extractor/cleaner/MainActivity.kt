package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil.compose.AsyncImage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.UninstallerTheme
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.EmeraldGreen
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.LogoPurple
import com.zeetech.uninstaller.bulk.apk.extractor.cleaner.ui.theme.Charcoal
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

    override fun onResume() {
        super.onResume()
        // Icons are now preserved on disk and handled by Coil.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Icons are now stored on disk and managed by Coil, so no RAM clearing needed here.
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val uiState: StateFlow<AppUiState> = _uiState

    // Set of package names whose icons are currently cached on disk
    private val _cachedIcons = MutableStateFlow<Set<String>>(emptySet())
    val cachedIcons: StateFlow<Set<String>> = _cachedIcons

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

    val appVersionName: String = try {
        val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        pInfo.versionName ?: "1.0.0"
    } catch (e: Exception) { "1.0.0" }

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
        loadApps(force = true)   // Force reload after uninstall
    }

    fun startDeepClean() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _uiState.emit(AppUiState.Scanning(0f, app.getString(R.string.state_analyzing_buffers)))
            
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
                            _uiState.emit(AppUiState.Scanning(0.4f + (index.toFloat()/totalApps.coerceAtLeast(1))*0.5f, getApplication<Application>().getString(R.string.state_surgical_scan)))
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
            val appCtx = getApplication<Application>()
            discoveredJunk.forEachIndexed { index, file ->
                _uiState.emit(AppUiState.Scanning(index.toFloat() / total, appCtx.getString(R.string.state_surgical_removal, file.name)))
                try {
                    val size = if (file.isDirectory) calculateFolderSize(file) else file.length()
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    spaceReclaimed += size
                } catch (e: Exception) { /* Ignore deletion errors */ }
            }
            
            _uiState.emit(AppUiState.CleanFinished)
            discoveredJunk = emptyList()
            // Reload storage stats and app list after a clean-up
            refreshList()
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
        // Restore cached list instantly — no reload needed
        val cached = cachedSuccessState
        if (cached != null) {
            viewModelScope.launch { _uiState.emit(cached) }
        } else {
            loadApps(force = true)
        }
    }

    private fun loadApps(force: Boolean = false) {
        // Skip reload if we already have cached data (e.g. returning from a popup)
        if (!force && cachedSuccessState != null) {
            viewModelScope.launch { _uiState.emit(cachedSuccessState!!) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
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
            val stat = StatFs(Environment.getDataDirectory().path)
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
                    // It's an App Bundle. Zip the base APK and all splits together.
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
    val isSystem: Boolean,
    val targetSdk: Int,
    val lastUsed: Long,
    val lastUpdated: Long,
    val installerStore: String?,
    val isSplitApk: Boolean = false  // True when Play Store installed as App Bundle (multiple APK splits)
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
                    onRefresh = if (currentScreen == "home") {{ viewModel.refreshList() }} else null,
                    onShareApp = {
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
                    val cachedIcons by viewModel.cachedIcons.collectAsState()

                    if (currentScreen == "home") {
                        HomeScreen(
                            apps = state.apps,
                            storage = state.storage,
                            cachedIcons = cachedIcons,
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
            Icon(Icons.Default.SearchOff, null, Modifier.size(80.dp), tint = EmeraldGreen.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(24.dp))
            Row {
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
    onShareApp: (() -> Unit)? = null
) {
    CenterAlignedTopAppBar(
        navigationIcon = {
            Row {
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.content_refresh),
                            tint = EmeraldGreen
                        )
                    }
                }
                if (onShareApp != null) {
                    IconButton(onClick = onShareApp) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.item_share),
                            tint = EmeraldGreen
                        )
                    }
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
                    contentDescription = stringResource(R.string.content_toggle_theme),
                    tint = if (isDarkTheme) Color.White else Charcoal
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = if (!showSettings) Icons.Default.Home else Icons.Default.Settings,
                    contentDescription = stringResource(R.string.content_navigate),
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
    cachedIcons: Set<String>,
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
                        Icon(Icons.Default.Sort, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
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
                            leadingIcon = { Icon(if (isAscending) Icons.Default.VerticalAlignTop else Icons.Default.VerticalAlignBottom, null) },
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
                                isIconCached = cachedIcons.contains(app.packageName),
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
                    Text(stringResource(R.string.action_surgical_clean), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                isIconCached = cachedIcons.contains(app.packageName),
                onDismiss = { appActionToShow = null },
                onUninstall = { 
                    onUninstallRequest(app.packageName)
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
                    Icon(Icons.Default.Android, null,
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
                            Icon(Icons.Default.Android, null,
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
                        Icon(Icons.Default.Launch, null, Modifier.size(18.dp))
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
                        Icon(if (app.isSplitApk) Icons.Default.FolderZip else Icons.Default.CloudDownload, null, Modifier.size(18.dp))
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
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_size), app.size, Icons.Default.SdStorage)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_target), "API ${app.targetSdk}", Icons.Default.DataObject)
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_last_used), lastUsedDate, Icons.Default.History)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_installed), installDate, Icons.Default.Event)
                    InfoBox(Modifier.weight(1f), stringResource(R.string.info_updated), updateDate, Icons.Default.Update)
                }
                Spacer(Modifier.height(6.dp))
                InfoBox(Modifier.fillMaxWidth(), stringResource(R.string.info_source), source, Icons.Default.Source)
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
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(12.dp), tint = LogoPurple)
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
            
            Button(
                onClick = onUninstall,
                colors = ButtonDefaults.buttonColors(containerColor = LogoPurple, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text(stringResource(R.string.action_uninstall), fontWeight = FontWeight.Bold)
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
                    Text(stringResource(R.string.subtitle_precision), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Version ${viewModel.appVersionName} • Zee Tech", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                            Icon(Icons.Default.FolderOpen, null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
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
                    icon = Icons.Default.Apps,
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
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/console/u/0/developers/8381881514749219665/app/4976725838031557021/main-store-listing"))
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                SettingsItem(
                    icon = Icons.Default.Email,
                    label = stringResource(R.string.item_feedback),
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
                    icon = Icons.Default.PrivacyTip,
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
                text = if (isGranted) stringResource(R.string.permission_allowed) else stringResource(R.string.permission_allow),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                color = if (isGranted) EmeraldGreen else Color.Red
            )
        }
    }
}
