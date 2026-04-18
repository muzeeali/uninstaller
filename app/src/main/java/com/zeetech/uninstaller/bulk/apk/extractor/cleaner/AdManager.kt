package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AdManager"

// ─── Ad Unit IDs ────────────────────────────────────────────────────────────
private const val AD_UNIT_APP_OPEN     = "ca-app-pub-6425054459696619/3748084648"
private const val AD_UNIT_INTERSTITIAL = "ca-app-pub-6425054459696619/8013363790"
private const val AD_UNIT_REWARDED     = "ca-app-pub-6425054459696619/1377906329"

// ─── Uncomment for development/testing to avoid policy violations ─────────────
// private const val AD_UNIT_APP_OPEN     = "ca-app-pub-3940256099942544/9257395921"
// private const val AD_UNIT_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
// private const val AD_UNIT_REWARDED     = "ca-app-pub-3940256099942544/5224354917"

object AdManager {
    const val DEBUG_SUPPRESS_ADS = false // Set to false for production

    // ─── Weak reference to Activity (avoids memory leaks) ────────────────────
    private var activityRef: WeakReference<Activity>? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    private fun currentActivity(): Activity? = activityRef?.get()

    // ─── Session Counters ────────────────────────────────────────────────────
    var rewardedUnlockActive = false   // lifted after watching rewarded ad


    // ─── Session Ad Counters (reset on process death / cold start) ────────────
    private var homeRefreshCount = 0                    // Trigger 1a: Home refresh, every 3rd
    private var historyRefreshCount = 0                 // Trigger 1b: History refresh, every 3rd
    private const val REFRESH_AD_EVERY = 3

    private var tabSwitchCount = 0                      // Trigger 4
    private const val TAB_SWITCH_AD_EVERY = 5

    private var settingsEntryCount = 0                  // Trigger 5
    private const val SETTINGS_AD_EVERY = 3

    private var homeNavCount = 0                        // Trigger 6
    private const val HOME_NAV_AD_EVERY = 5

    private var selectAllCount = 0                      // Trigger 3: Select All, every 3rd
    private const val SELECT_ALL_AD_EVERY = 3

    private var selectionAdFiredThisCycle = false       // Trigger 2: rearms at count==0

    // ─── Ad Objects ──────────────────────────────────────────────────────────
    private var appCtx: Context? = null
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdLoading = false

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    
    // ── Global Ad Sync Flag ──────────────────────────────────────────────────
    private var isAdShowing = false

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false
    
    private val _isRewardedReadyFlow = MutableStateFlow(false)
    val isRewardedReadyFlow = _isRewardedReadyFlow.asStateFlow()

    // ─── Initialization ──────────────────────────────────────────────────────

    fun initialize(activity: Activity) {
        appCtx = activity.applicationContext
        bindActivity(activity)
        MobileAds.initialize(activity.applicationContext) {
            Log.d(TAG, "MobileAds initialized")
            loadInterstitial()
            loadRewarded()
            loadAppOpen()
        }
    }

    // ─── App Open ────────────────────────────────────────────────────────────

    private fun loadAppOpen() {
        val ctx = appCtx ?: return
        if (appOpenAd != null || appOpenAdLoading) return
        appOpenAdLoading = true
        AppOpenAd.load(
            ctx,
            AD_UNIT_APP_OPEN,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    appOpenAdLoading = false
                    Log.d(TAG, "App open ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAdLoading = false
                    Log.w(TAG, "App open ad failed: ${error.message}")
                }
            }
        )
    }


    fun showAppOpenAdIfAvailable(onDismiss: () -> Unit = {}) {
        if (DEBUG_SUPPRESS_ADS) {
            Log.d(TAG, "Ad skipped (DEBUG_SUPPRESS_ADS=true)")
            onDismiss()
            return
        }
        val activity = currentActivity() ?: run { onDismiss(); return }
        val ad = appOpenAd ?: run { onDismiss(); return }
        if (isAdShowing) { onDismiss(); return }

        isAdShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isAdShowing = false
                appOpenAd = null
                loadAppOpen()
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isAdShowing = false
                appOpenAd = null
                loadAppOpen()
                onDismiss()
            }
        }
        ad.show(activity)
    }

    // ─── Interstitial ────────────────────────────────────────────────────────

    private fun loadInterstitial() {
        val ctx = appCtx ?: return
        if (interstitialAd != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(
            ctx,
            AD_UNIT_INTERSTITIAL,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    interstitialLoading = false
                    Log.d(TAG, "Interstitial loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Log.w(TAG, "Interstitial failed: ${error.message}")
                }
            }
        )
    }

    fun showInterstitial(onDismiss: () -> Unit = {}, forceAlways: Boolean = false) {
        // Note: The forceAlways parameter is no longer strictly required for bypassing
        // time limits since the time-based cooldown was removed. However, it remains
        // in the signature to explicitly denote premium gate triggers within the codebase.
        if (DEBUG_SUPPRESS_ADS) {
            Log.d(TAG, "Ad skipped (DEBUG_SUPPRESS_ADS=true)")
            onDismiss()
            return
        }
        val activity = currentActivity() ?: run { onDismiss(); return }

        val ad = interstitialAd
        if (ad == null || isAdShowing) {
            loadInterstitial()
            onDismiss()
            return
        }
        isAdShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isAdShowing = false
                interstitialAd = null
                loadInterstitial()
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isAdShowing = false
                interstitialAd = null
                loadInterstitial()
                onDismiss()
            }
        }
        ad.show(activity)
    }

    // ─── Contextual interstitial triggers ────────────────────────────────────

    // Trigger 1a — Home Refresh (1, 4, 7, 10...)
    fun onHomeRefreshTapped() {
        homeRefreshCount++
        if (homeRefreshCount % REFRESH_AD_EVERY == 1) {
            showInterstitial()
        }
    }

    // Trigger 1b — History Refresh (1, 4, 7, 10... separate counter)
    fun onHistoryRefreshTapped() {
        historyRefreshCount++
        if (historyRefreshCount % REFRESH_AD_EVERY == 1) {
            showInterstitial()
        }
    }

    // Trigger 2 — Selection count
    fun onSelectionChanged(newCount: Int) {
        if (newCount == 0) {
            selectionAdFiredThisCycle = false   // rearm for next cycle
        }
        if (newCount >= 3 && !selectionAdFiredThisCycle) {
            selectionAdFiredThisCycle = true
            showInterstitial()
        }
    }

    // Trigger 3 — Select All (1, 4, 7, 10... independent counter)
    fun onSelectAll() {
        selectAllCount++
        if (selectAllCount % SELECT_ALL_AD_EVERY == 1) {
            showInterstitial()
        }
        selectionAdFiredThisCycle = true
    }

    // Trigger 4 — Tab switch (1, 5, 10, 15...)
    fun onTabSwitched() {
        tabSwitchCount++
        if (tabSwitchCount > 0 && tabSwitchCount % TAB_SWITCH_AD_EVERY == 0) {
            showInterstitial()
        }
    }

    // Trigger 5 — Enter Settings (1, 4, 7, 10...)
    fun onEnteredSettings() {
        settingsEntryCount++
        if (settingsEntryCount % SETTINGS_AD_EVERY == 1) {
            showInterstitial()
        }
    }

    // Trigger 6 — Navigate to Home from ANYWHERE (1, 5, 10, 15...)
    fun onNavigatedToHome() {
        homeNavCount++
        if (homeNavCount > 0 && homeNavCount % HOME_NAV_AD_EVERY == 0) {
            showInterstitial()
        }
    }

    // ─── Rewarded ────────────────────────────────────────────────────────────

    private fun loadRewarded() {
        val ctx = appCtx ?: return
        if (rewardedAd != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(
            ctx,
            AD_UNIT_REWARDED,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardedLoading = false
                    _isRewardedReadyFlow.value = true
                    Log.d(TAG, "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedLoading = false
                    _isRewardedReadyFlow.value = false
                    Log.w(TAG, "Rewarded failed: ${error.message}")
                }
            }
        )
    }

    fun showRewarded(onRewardEarned: () -> Unit, onDismiss: () -> Unit = {}) {
        if (DEBUG_SUPPRESS_ADS) {
            Log.d(TAG, "Ad skipped (DEBUG_SUPPRESS_ADS=true). Rewarding immediately.")
            rewardedUnlockActive = true
            onRewardEarned()
            return
        }
        val activity = currentActivity() ?: run { onDismiss(); return }
        val ad = rewardedAd
        if (ad == null || isAdShowing) {
            loadRewarded()
            onDismiss()
            return
        }
        isAdShowing = true
        var isRewardEarned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isAdShowing = false
                rewardedAd = null
                _isRewardedReadyFlow.value = false
                loadRewarded()
                if (isRewardEarned) {
                    onRewardEarned()
                }
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isAdShowing = false
                rewardedAd = null
                _isRewardedReadyFlow.value = false
                loadRewarded()
                onDismiss()
            }
        }
        ad.show(activity) { _ ->
            rewardedUnlockActive = true
            isRewardEarned = true
        }
    }

    fun isRewardedReady() = rewardedAd != null
}
