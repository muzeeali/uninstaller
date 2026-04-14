package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

class UpdateManager(private val context: Context) {

    companion object {
        const val UPDATE_REQUEST_CODE = 1001 // Kept for backward compatibility if ever needed
    }

    private val TAG = "UpdateManager"
    private val appUpdateManager = AppUpdateManagerFactory.create(context)
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    init {
        val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebuggable) 0 else 3600 
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(mapOf(
            "latest_version_code" to 1L,
            "update_url" to "https://play.google.com/store/apps/details?id=${context.packageName}",
            "fallback_url" to "https://play.google.com/store/apps/details?id=${context.packageName}",
            "is_update_mandatory" to false,
            "is_test_update" to false
        ))
    }

    /**
     * Main entry point to check for updates
     */
    fun checkForUpdates(
        updateLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
        onFlexibleUpdateDownloaded: () -> Unit,
        onManualUpdateAvailable: (url: String, isMandatory: Boolean) -> Unit
    ) {
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            val isMandatory = if (task.isSuccessful) remoteConfig.getBoolean("is_update_mandatory") else false
            
            if (isGooglePlaySource()) {
                Log.d(TAG, "Source: Google Play. Flow: ${if (isMandatory) "IMMEDIATE" else "FLEXIBLE"}")
                checkPlayStoreUpdate(updateLauncher, isMandatory, onFlexibleUpdateDownloaded)
            } else {
                Log.d(TAG, "Source: Other/APK. Checking Firebase...")
                // In APK path, if test_update is true, we force it for UI verification
                val isTestUpdate = if (task.isSuccessful) remoteConfig.getBoolean("is_test_update") else false
                checkFirebaseUpdate(isMandatory, isTestUpdate, onManualUpdateAvailable)
            }
        }
    }

    private fun isGooglePlaySource(): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(context.packageName)
                info.installingPackageName == "com.android.vending"
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName) == "com.android.vending"
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun checkPlayStoreUpdate(updateLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>, isMandatory: Boolean, onDownloaded: () -> Unit) {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                val updateType = if (isMandatory) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
                
                if (appUpdateInfo.isUpdateTypeAllowed(updateType)) {
                    if (updateType == AppUpdateType.FLEXIBLE) {
                        // Register listener for flexible download progress
                        val listener = object : InstallStateUpdatedListener {
                            override fun onStateUpdate(state: com.google.android.play.core.install.InstallState) {
                                if (state.installStatus() == InstallStatus.DOWNLOADED) {
                                    onDownloaded()
                                    appUpdateManager.unregisterListener(this)
                                } else if (state.installStatus() == InstallStatus.CANCELED || state.installStatus() == InstallStatus.FAILED) {
                                    appUpdateManager.unregisterListener(this)
                                }
                            }
                        }
                        appUpdateManager.registerListener(listener)
                    }

                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        updateLauncher,
                        AppUpdateOptions.newBuilder(updateType).build()
                    )
                }
            }
        }
    }

    /**
     * Call this in Activity.onResume to re-trigger immediate updates if they were canceled
     */
    fun checkOngoingUpdate(updateLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Resume an immediate update
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    private fun checkFirebaseUpdate(isMandatory: Boolean, isTestUpdate: Boolean, onUpdateFound: (String, Boolean) -> Unit) {
        val latestVersion = remoteConfig.getLong("latest_version_code")
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) { 0L }

        // If test flag is true OR real version check passes
        if (isTestUpdate || latestVersion > currentVersion) {
            val url = if (isPlayStoreInstalled()) {
                remoteConfig.getString("update_url")
            } else {
                remoteConfig.getString("fallback_url")
            }
            onUpdateFound(url, isMandatory)
        }
    }

    private fun isPlayStoreInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.android.vending", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    fun launchUpdateUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch update URL: $url")
        }
    }
}
