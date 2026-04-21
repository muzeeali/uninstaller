package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.ads.mediation.admob.AdMobAdapter
// No import needed for BuildConfig in same package
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.SharedPreferences
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

private const val TAG = "AdManager"

// ─── Ad Unit IDs ────────────────────────────────────────────────────────────
private const val AD_UNIT_APP_OPEN     = "ca-app-pub-6425054459696619/3748084648"
private const val AD_UNIT_INTERSTITIAL = "ca-app-pub-6425054459696619/8013363790"
private const val AD_UNIT_REWARDED     = "ca-app-pub-6425054459696619/1377906329"

// NOTE: Test ad unit IDs removed from source. Production IDs below are used.

object AdManager {
    const val DEBUG_SUPPRESS_ADS = false // Set to false for production

    // Weak activity reference
    private var activityRef: WeakReference<Activity>? = null
    private fun currentActivity(): Activity? = activityRef?.get()
    fun bindActivity(activity: Activity) { activityRef = WeakReference(activity) }

    // Application context
    private var appCtx: Context? = null

    // Ad unit IDs (production)
    private val AD_UNIT_APP_OPEN: String = "ca-app-pub-6425054459696619/3748084648"
    private val AD_UNIT_INTERSTITIAL: String = "ca-app-pub-6425054459696619/8013363790"
    private val AD_UNIT_REWARDED: String = "ca-app-pub-6425054459696619/1377906329"
    private val AD_UNIT_BANNER: String = "ca-app-pub-6425054459696619/9326445462"

    // Consent / personalization flags
    private var isPersonalizedAdsAllowed: Boolean = true

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
    private const val PREFS_NAME = "ad_manager_prefs"
    private const val KEY_INTERSTITIAL_COUNT = "interstitial_event_count"
    private var prefs: SharedPreferences? = null

    // Preserve selection-cycle semantics (only fire once while user has >=3 selected)
    private var selectionAdFiredThisCycle = false

    // Critical-flow suppression (e.g., uninstall flow, permission dialogs)
    @Volatile
    private var suppressInterstitials = false

    // Initialize and preload
    fun initialize(activity: Activity) {
        appCtx = activity.applicationContext
        bindActivity(activity)
        prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Load persisted interstitial counter
        try {
            interstitialEventCount = prefs?.getInt(KEY_INTERSTITIAL_COUNT, 0) ?: 0
        } catch (e: Exception) {
            interstitialEventCount = 0
        }
        MobileAds.initialize(activity.applicationContext) {
            Log.d(TAG, "MobileAds initialized")
            // Request consent first (if UMP available), then load ads
            requestConsent(activity) {
                loadInterstitial()
                loadRewarded()
                loadAppOpen()
            }
        }
    }

    // --- UMP consent helper
    private fun requestConsent(activity: Activity, onComplete: () -> Unit) {
        try {
            val params = ConsentRequestParameters.Builder().build()
            val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
            consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                {
                    try {
                        if (consentInformation.isConsentFormAvailable) {
                            UserMessagingPlatform.loadConsentForm(
                                activity,
                                { form ->
                                    form.show(activity) { /* dismissed */
                                        isPersonalizedAdsAllowed = consentInformation.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
                                        onComplete()
                                    }
                                },
                                { error ->
                                    Log.w(TAG, "Consent form load failed: ${error.message}")
                                    isPersonalizedAdsAllowed = consentInformation.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
                                    onComplete()
                                }
                            )
                        } else {
                            isPersonalizedAdsAllowed = consentInformation.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
                            onComplete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Consent form flow failed: ${e.message}")
                        onComplete()
                    }
                },
                { error ->
                    Log.w(TAG, "Consent info update failed: ${error.message}")
                    onComplete()
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "UMP not available or error: ${e.message}")
            onComplete()
        }
    }

    // --- Helpers: cooldown enforcement
    private fun canShowFullScreen(): Boolean {
        if (DEBUG_SUPPRESS_ADS) return false
        if (suppressInterstitials) return false
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

    private fun persistInterstitialCount() {
        try {
            prefs?.edit()?.putInt(KEY_INTERSTITIAL_COUNT, interstitialEventCount)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist interstitial count: ${e.message}")
        }
    }

    // --- AdRequest builder honoring consent
    private fun buildAdRequest(): AdRequest {
        val builder = AdRequest.Builder()
        if (!isPersonalizedAdsAllowed) {
            val extras = Bundle()
            extras.putString("npa", "1")
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return builder.build()
    }

    // --- App Open
    private fun loadAppOpen() {
        val ctx = appCtx ?: return
        if (appOpenAd != null || appOpenAdLoading) return
        appOpenAdLoading = true
        AppOpenAd.load(
            ctx,
            AD_UNIT_APP_OPEN,
            buildAdRequest(),
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
            buildAdRequest(),
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

    fun showInterstitial(onDismiss: () -> Unit = {}) {
        if (DEBUG_SUPPRESS_ADS) { onDismiss(); return }
        val activity = currentActivity() ?: run { onDismiss(); return }

        // All interstitials must respect global cooldowns/caps
        if (!canShowFullScreen()) {
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
            buildAdRequest(),
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
        adView.loadAd(buildAdRequest())
        return adView
    }

    fun destroyBanner(adView: AdView?) { adView?.destroy() }

    // --- Contextual trigger wrappers (preserve existing call sites)
    /**
     * Record an interstitial-triggering event. By default this will show an interstitial every
     * INTERSTITIAL_AD_EVERY events, but callers may set allowShow=false to increment the counter
     * without attempting to show (useful for critical flows like uninstall/permission dialogs).
     */
    private fun recordInterstitialEventAndMaybeShow(onDismiss: () -> Unit = {}, allowShow: Boolean = true) {
        interstitialEventCount++
        persistInterstitialCount()
        if (allowShow && interstitialEventCount % INTERSTITIAL_AD_EVERY == 0) showInterstitial(onDismiss)
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

    // --- Semantic wrappers for direct callsites (preserve unified counting + allow onDismiss)
    fun onExtractTapped(onDismiss: () -> Unit = {}) {
        recordInterstitialEventAndMaybeShow(onDismiss)
    }

    fun onNavigateToHistory(onDismiss: () -> Unit = {}) {
        recordInterstitialEventAndMaybeShow(onDismiss)
    }

    fun onCleanFinishedDone(onDismiss: () -> Unit = {}) {
        recordInterstitialEventAndMaybeShow(onDismiss)
    }

    fun onCleanFinishedCancel(onDismiss: () -> Unit = {}) {
        // Consider not showing on cancel; keep unified behavior but allow suppression here if desired
        recordInterstitialEventAndMaybeShow(onDismiss)
    }

    fun onUninstallConfirmed(onDismiss: () -> Unit = {}) {
        // Uninstall is a critical system flow — increment counter but do not show an interstitial now.
        recordInterstitialEventAndMaybeShow(onDismiss, allowShow = false)
    }

    /**
     * API for UI flows that want to suppress showing interstitials temporarily (e.g., while
     * permission dialogs or uninstall confirmations are visible). Call `beginCriticalFlow()`
     * before showing the critical dialog and `endCriticalFlow()` after it is dismissed.
     */
    fun beginCriticalFlow() { suppressInterstitials = true }
    fun endCriticalFlow() { suppressInterstitials = false }
}
