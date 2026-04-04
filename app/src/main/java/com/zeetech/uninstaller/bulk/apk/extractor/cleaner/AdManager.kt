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

    // ─── Weak reference to Activity (avoids memory leaks) ────────────────────
    private var activityRef: WeakReference<Activity>? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    private fun currentActivity(): Activity? = activityRef?.get()

    // ─── Session Counters ────────────────────────────────────────────────────
    var rewardedUnlockActive = false   // lifted after watching rewarded ad


    // Selection threshold ladder: 3, 5, 10
    private val selectionThresholds = listOf(3, 5, 10)
    private val triggeredThresholds = mutableSetOf<Int>()

    // ─── Ad Objects ──────────────────────────────────────────────────────────
    private var appCtx: Context? = null
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdLoading = false

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    private var lastInterstitialShowTime: Long = 0
    private const val INTERSTITIAL_COOLDOWN_MS = 120_000L // 2 Minutes
    
    // ── Global Ad Sync Flag ──────────────────────────────────────────────────
    private var isAdShowing = false

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false

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

    fun showInterstitial(onDismiss: () -> Unit = {}, force: Boolean = false) {
        val activity = currentActivity() ?: run { onDismiss(); return }
        
        // Smart Cooldown: Avoid spamming interstitials (3 Minutes)
        // Bypass if this is a "Premium" forced action (Extract/History)
        val now = System.currentTimeMillis()
        if (!force && now - lastInterstitialShowTime < INTERSTITIAL_COOLDOWN_MS) {
            Log.d(TAG, "Interstitial ignored: Cooldown active")
            onDismiss()
            return
        }

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
                lastInterstitialShowTime = System.currentTimeMillis()
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

    fun onRefreshTapped() {
        showInterstitial()
    }

    fun onTabSwitched() {
        showInterstitial()
    }

    fun onExitedSettings() {
        showInterstitial()
    }

    fun onSelectionChanged(newCount: Int) {
        for (threshold in selectionThresholds) {
            if (newCount >= threshold && !triggeredThresholds.contains(threshold)) {
                triggeredThresholds.add(threshold)
                showInterstitial()
                break
            }
        }
    }

    fun resetSelectionThresholds() {
        triggeredThresholds.clear()
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
                    Log.d(TAG, "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedLoading = false
                    Log.w(TAG, "Rewarded failed: ${error.message}")
                }
            }
        )
    }

    fun showRewarded(onRewardEarned: () -> Unit, onDismiss: () -> Unit = {}) {
        val activity = currentActivity() ?: run { onDismiss(); return }
        val ad = rewardedAd
        if (ad == null || isAdShowing) {
            loadRewarded()
            onDismiss()
            return
        }
        isAdShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isAdShowing = false
                rewardedAd = null
                loadRewarded()
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isAdShowing = false
                rewardedAd = null
                loadRewarded()
                onDismiss()
            }
        }
        ad.show(activity) { _ ->
            rewardedUnlockActive = true
            onRewardEarned()
        }
    }

    fun isRewardedReady() = rewardedAd != null
}
