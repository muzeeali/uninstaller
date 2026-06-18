package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FeatureFlags {
    private val _shredderDuplicatesFree = MutableStateFlow(true)
    val shredderDuplicatesFree = _shredderDuplicatesFree.asStateFlow()

    private val _shredderLargeFilesFree = MutableStateFlow(true)
    val shredderLargeFilesFree = _shredderLargeFilesFree.asStateFlow()

    fun initialize() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        
        // Read current cache or default values immediately
        _shredderDuplicatesFree.value = remoteConfig.getBoolean("shredder_duplicates_free")
        _shredderLargeFilesFree.value = remoteConfig.getBoolean("shredder_large_files_free")

        // Fetch updates from network
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _shredderDuplicatesFree.value = remoteConfig.getBoolean("shredder_duplicates_free")
                _shredderLargeFilesFree.value = remoteConfig.getBoolean("shredder_large_files_free")
            }
        }
    }
}
