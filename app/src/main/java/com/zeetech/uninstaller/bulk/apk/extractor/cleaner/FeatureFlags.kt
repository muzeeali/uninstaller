package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FeatureFlags {
    private val _shredderDuplicatesFree = MutableStateFlow(true)
    val shredderDuplicatesFree = _shredderDuplicatesFree.asStateFlow()

    private val _shredderLargeFilesFree = MutableStateFlow(true)
    val shredderLargeFilesFree = _shredderLargeFilesFree.asStateFlow()

    private val _shredRewardedAdEnabled = MutableStateFlow(true)
    val shredRewardedAdEnabled = _shredRewardedAdEnabled.asStateFlow()

    fun initialize() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        
        // Read current cache or default values immediately
        _shredderDuplicatesFree.value = remoteConfig.getBoolean("shredder_duplicates_free")
        _shredderLargeFilesFree.value = remoteConfig.getBoolean("shredder_large_files_free")
        _shredRewardedAdEnabled.value = if (remoteConfig.all.containsKey("shred_rewarded_ad_enabled")) {
            remoteConfig.getBoolean("shred_rewarded_ad_enabled")
        } else true

        // Fetch updates from network
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _shredderDuplicatesFree.value = remoteConfig.getBoolean("shredder_duplicates_free")
                _shredderLargeFilesFree.value = remoteConfig.getBoolean("shredder_large_files_free")
                _shredRewardedAdEnabled.value = if (remoteConfig.all.containsKey("shred_rewarded_ad_enabled")) {
                    remoteConfig.getBoolean("shred_rewarded_ad_enabled")
                } else true
            }
        }
    }
}
