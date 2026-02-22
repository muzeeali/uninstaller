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
import android.provider.Settings
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
import androidx.compose.material3.*
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

    private var discoveredJunk = listOf<File>()

    init {
        loadApps()
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

    fun performCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            val total = discoveredJunk.size
            discoveredJunk.forEachIndexed { index, file ->
                _uiState.emit(AppUiState.Scanning(index.toFloat() / total, "Deleting: ${file.name}..."))
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
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
            val pm = getApplication<Application>().packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
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

                AppInfo(
                    name = name,
                    packageName = pkgName,
                    version = version,
                    installedDate = installedDate,
                    size = size,
                    sizeBytes = sizeInBytes,
                    icon = icon,
                    isSystem = isSystem,
                    targetSdk = appInfo.targetSdkVersion
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
    val targetSdk: Int
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
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            if (uiState !is AppUiState.Scanning && uiState !is AppUiState.CleanSummary) {
                UninstallerTopBar(
                    title = if (currentScreen == "home") "UNINSTALLER" else "SETTINGS",
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    onSettingsClick = { 
                        currentScreen = if (currentScreen == "home") "settings" else "home" 
                    },
                    showSettings = currentScreen == "home"
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = uiState) {
                is AppUiState.Loading -> LoadingScreen()
                is AppUiState.Empty -> EmptyStateScreen { viewModel.refreshList() }
                is AppUiState.Scanning -> DeepCleanProgressScreen(state.progress, state.currentTask)
                is AppUiState.CleanSummary -> CleanupSummaryScreen(
                    space = state.spaceFound, 
                    itemsCount = state.itemsCount,
                    onClean = { viewModel.performCleanup() },
                    onCancel = { viewModel.backToHome() }
                )
                is AppUiState.CleanFinished -> CleanFinishedScreen { viewModel.backToHome() }
                is AppUiState.Success -> {
                    if (currentScreen == "home") {
                        HomeScreen(
                            apps = state.apps,
                            storage = state.storage,
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
                            }
                        )
                    } else {
                        SettingsScreen()
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
    showSettings: Boolean
) {
    CenterAlignedTopAppBar(
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

@Composable
fun HomeScreen(apps: List<AppInfo>, storage: StorageInfo, onUninstallRequest: (String) -> Unit, onDeepCleanStart: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedApps = remember { mutableStateListOf<AppInfo>() }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    
    // Sorting States
    var sortBy by remember { mutableStateOf("Name") }
    var isAscending by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredApps = remember(searchQuery, apps, sortBy, isAscending) {
        val filtered = apps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        val sorted = when (sortBy) {
            "Size" -> filtered.sortedBy { it.sizeBytes }
            "Date" -> filtered.sortedBy { it.installedDate }
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 1. Unified Search & Sort Row (Ultra-Compact: 36dp)
            Spacer(modifier = Modifier.height(8.dp))
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
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    ),
                    decorationBox = { innerTextField ->
                        Row(
                            Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) Text("Search...", fontSize = 13.sp, color = Color.Gray)
                                innerTextField()
                            }
                        }
                    }
                )

                Box(modifier = Modifier.fillMaxHeight()) {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .background(EmeraldGreen.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Sort, null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
                    }

                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        Text("Sort By", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        listOf("Name", "Size", "Date").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                trailingIcon = { if(sortBy == option) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                                onClick = { sortBy = option; showSortMenu = false }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = { Text(if (isAscending) "Ascending" else "Descending") },
                            leadingIcon = { Icon(if (isAscending) Icons.Default.VerticalAlignTop else Icons.Default.VerticalAlignBottom, null) },
                            onClick = { isAscending = !isAscending; showSortMenu = false }
                        )
                    }
                }
            }

            // 2. Free Memory & Total Apps Row (Ultra-Compact: 36dp)
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Free Memory: ${storage.free} / ${storage.total}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Apps: ${apps.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldGreen
                    )
                }
            }

            // 3. Compact Tabs & Select All
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentVisibleApps = if (pagerState.currentPage == 0) userApps else systemApps
                val isAllSelected = selectedApps.isNotEmpty() && currentVisibleApps.isNotEmpty() && selectedApps.size >= currentVisibleApps.size
                
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

            // 4. Content Pager (Maximizing List Space)
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
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
                }
            )
        }
}

@Composable
fun AppRow(app: AppInfo, isSelected: Boolean, onToggle: () -> Unit, onMenuClick: () -> Unit) {
    val canUninstall = !app.isSystem
    
    Surface(
        onClick = { if (canUninstall) onToggle() },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = if (isSelected) LogoPurple.copy(alpha = 0.12f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.3f)) else null,
        enabled = canUninstall
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
    onDetails: () -> Unit
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
                // Info Grid (2 boxes per row)
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                val installDate = sdf.format(java.util.Date(app.installedDate))
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBox(Modifier.weight(1f), "VERSION", app.version, Icons.Default.Info)
                    InfoBox(Modifier.weight(1f), "SIZE", app.size, Icons.Default.SdStorage)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBox(Modifier.weight(1f), "TARGET", "API ${app.targetSdk}", Icons.Default.DataObject)
                    InfoBox(Modifier.weight(1f), "INSTALLED", installDate, Icons.Default.Event)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Branded Action Buttons
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!app.isSystem) {
                        Button(
                            onClick = onUninstall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("UNINSTALL", fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Launch, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("LAUNCH", fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDetails,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LogoPurple.copy(alpha = 0.1f), contentColor = LogoPurple),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LogoPurple.copy(alpha = 0.2f))
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SYSTEM SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Black)
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
fun SettingsScreen() {
    val context = LocalContext.current
    
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
fun SettingsToggle(label: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { checked = !checked }.padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked, 
            onCheckedChange = { checked = it }, 
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
