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

    // Weak activity reference
    private var activityRef: WeakReference<Activity>? = null
    private fun currentActivity(): Activity? = activityRef?.get()
    fun bindActivity(activity: Activity) { activityRef = WeakReference(activity) }

    // Application context
    private var appCtx: Context? = null

    // Ad unit IDs
    private const val AD_UNIT_APP_OPEN     = "ca-app-pub-6425054459696619/3748084648"
    private const val AD_UNIT_INTERSTITIAL = "ca-app-pub-6425054459696619/8013363790"
    private const val AD_UNIT_REWARDED     = "ca-app-pub-6425054459696619/1377906329"
    private const val AD_UNIT_BANNER       = "ca-app-pub-6425054459696619/9326445462"

    // Single-active ad lock
    private var isAdShowing = false

    // AppOpen
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdLoading = false
    private var appOpenShownThisColdStart = false

    // Interstitial
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false

    // Rewarded
    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false
    private val _isRewardedReadyFlow = MutableStateFlow(false)
    val isRewardedReadyFlow = _isRewardedReadyFlow.asStateFlow()
    var rewardedUnlockActive = false

    // --- Policy / UX safety: cooldowns and caps
    private const val MIN_FULL_SCREEN_INTERVAL_MS = 90_000L // 90s minimum between full-screen ads
    private const val WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    private const val MAX_INTERSTITIALS_PER_WINDOW = 3
    private var lastFullScreenShownAt = 0L
    private val fullScreenTimestamps: MutableList<Long> = mutableListOf()

    // Single universal interstitial event counter (used by all contextual triggers)
    @Volatile
    private var interstitialEventCount = 0
    private const val INTERSTITIAL_AD_EVERY = 3 // show interstitial every N events

    // Preserve selection-cycle semantics (only fire once while user has >=3 selected)
    private var selectionAdFiredThisCycle = false

    // Initialize and preload
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

    // --- Helpers: cooldown enforcement
    private fun canShowFullScreen(): Boolean {
        if (DEBUG_SUPPRESS_ADS) return false
        val now = System.currentTimeMillis()
        if (isAdShowing) return false
        if (now - lastFullScreenShownAt < MIN_FULL_SCREEN_INTERVAL_MS) return false

        // Prune old timestamps
        fullScreenTimestamps.removeIf { it < now - WINDOW_MS }
        if (fullScreenTimestamps.size >= MAX_INTERSTITIALS_PER_WINDOW) return false
        return true
    }

    private fun noteFullScreenShown() {
        val now = System.currentTimeMillis()
        lastFullScreenShownAt = now
        fullScreenTimestamps.add(now)
    }

    // --- App Open
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
        if (DEBUG_SUPPRESS_ADS) { onDismiss(); return }
        if (appOpenShownThisColdStart) { onDismiss(); return }
        if (!canShowFullScreen()) { onDismiss(); return }
        val activity = currentActivity() ?: run { onDismiss(); return }
        val ad = appOpenAd ?: run { onDismiss(); return }

        isAdShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isAdShowing = false
                appOpenAd = null
                appOpenShownThisColdStart = true
                noteFullScreenShown()
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

    // --- Interstitial
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
        if (DEBUG_SUPPRESS_ADS) { onDismiss(); return }
        val activity = currentActivity() ?: run { onDismiss(); return }

        if (!forceAlways && !canShowFullScreen()) {
            // If forced (premium flow), allow bypassing cooldown
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
                noteFullScreenShown()
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

    // --- Rewarded
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
                if (isRewardEarned) onRewardEarned()
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

    // --- Banner helper (returns configured AdView)
    fun createAdaptiveBanner(context: Context): AdView {
        val adView = AdView(context).apply {
            val displayMetrics = context.resources.displayMetrics
            val adWidth = (displayMetrics.widthPixels / displayMetrics.density).toInt()
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth))
            adUnitId = AD_UNIT_BANNER
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        adView.loadAd(AdRequest.Builder().build())
        return adView
    }

    fun destroyBanner(adView: AdView?) { adView?.destroy() }

    // --- Contextual trigger wrappers (preserve existing call sites)
    private fun recordInterstitialEventAndMaybeShow() {
        interstitialEventCount++
        if (interstitialEventCount % INTERSTITIAL_AD_EVERY == 0) showInterstitial()
    }

    fun onHomeRefreshTapped() {
        recordInterstitialEventAndMaybeShow()
    }

    fun onHistoryRefreshTapped() {
        recordInterstitialEventAndMaybeShow()
    }

    fun onSelectionChanged(newCount: Int) {
        if (newCount == 0) selectionAdFiredThisCycle = false
        if (newCount >= 3 && !selectionAdFiredThisCycle) {
            selectionAdFiredThisCycle = true
            recordInterstitialEventAndMaybeShow()
        }
    }

    fun onSelectAll() {
        selectionAdFiredThisCycle = true
        recordInterstitialEventAndMaybeShow()
    }

    fun onTabSwitched() {
        recordInterstitialEventAndMaybeShow()
    }

    fun onEnteredSettings() {
        recordInterstitialEventAndMaybeShow()
    }

    fun onNavigatedToHome() {
        recordInterstitialEventAndMaybeShow()
    }
}
