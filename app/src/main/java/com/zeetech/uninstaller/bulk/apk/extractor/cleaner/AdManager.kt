package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.os.Bundle
// Logging routed through Logger to avoid debug output in production
import java.lang.ref.WeakReference
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.ads.mediation.admob.AdMobAdapter
// No import needed for BuildConfig in same package
import com.google.android.gms.ads.AdListener
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
    private var pendingShowAppOpenOnLoad = false
    // Tracks whether the application lifecycle onStart has run at least once.
    // Used by MainActivity to decide first-start behavior across Activity recreations.
    var appLifecycleFirstStart = true

    // Interstitial
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false

    // Rewarded
    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false
    private val _isRewardedReadyFlow = MutableStateFlow(false)
    val isRewardedReadyFlow = _isRewardedReadyFlow.asStateFlow()
    var rewardedUnlockActive = false

    // Note: Interstitial frequency is controlled by event-based throttle (INTERSTITIAL_AD_EVERY).

    // Single universal interstitial event counter (used by all contextual triggers)
    @Volatile
    private var interstitialEventCount = 0
    private const val INTERSTITIAL_AD_EVERY = 3 // show interstitial every N events
    // No persistence — counter is in-memory only

    // Preserve selection-cycle semantics (only fire once while user has >=3 selected)
    private var selectionAdFiredThisCycle = false

    // No critical-flow suppression — event-based throttle controls shows

    // Initialize and preload
    fun initialize(activity: Activity) {
        appCtx = activity.applicationContext
        bindActivity(activity)
        // Mark that we want to show an App Open on this cold start when it's available
        pendingShowAppOpenOnLoad = true
        MobileAds.initialize(activity.applicationContext) {
            Logger.d(TAG, "MobileAds initialized")
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
                                    Logger.w(TAG, "Consent form load failed: ${error.message}")
                                    isPersonalizedAdsAllowed = consentInformation.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
                                    onComplete()
                                }
                            )
                        } else {
                            isPersonalizedAdsAllowed = consentInformation.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
                            onComplete()
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Consent form flow failed: ${e.message}")
                        onComplete()
                    }
                },
                { error ->
                    Logger.w(TAG, "Consent info update failed: ${error.message}")
                    onComplete()
                }
            )
        } catch (e: Exception) {
            Logger.w(TAG, "UMP not available or error: ${e.message}")
            onComplete()
        }
    }

    // --- Helpers: cooldown enforcement
    private fun canShowFullScreen(): Boolean {
        if (DEBUG_SUPPRESS_ADS) return false
        if (isAdShowing) return false
        return true
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
                    Logger.d(TAG, "App open ad loaded")
                    if (pendingShowAppOpenOnLoad && !appOpenShownThisColdStart) {
                        pendingShowAppOpenOnLoad = false
                        // show when available
                        // run on UI thread via current activity if possible
                        currentActivity()?.runOnUiThread {
                            try {
                                showAppOpenAdIfAvailable()
                            } catch (_: Exception) {}
                        }
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAdLoading = false
                    Logger.w(TAG, "App open ad failed: ${error.message}")
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
                    Logger.d(TAG, "Interstitial loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Logger.w(TAG, "Interstitial failed: ${error.message}")
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
                    Logger.d(TAG, "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedLoading = false
                    _isRewardedReadyFlow.value = false
                    Logger.w(TAG, "Rewarded failed: ${error.message}")
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
    fun createAdaptiveBanner(
        context: Context,
        onLoaded: () -> Unit = {},
        onFailed: () -> Unit = {}
    ): AdView {
        val adView = AdView(context).apply {
            val displayMetrics = context.resources.displayMetrics
            val adWidth = (displayMetrics.widthPixels / displayMetrics.density).toInt()
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth))
            adUnitId = AD_UNIT_BANNER
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Ensure the native AdView has no background/padding so the Compose
            // layout behind it shows through and there is no visible "cover".
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            try {
                // AdView extends ViewGroup; disable clipping to padding when available.
                this.setClipToPadding(false)
            } catch (_: Exception) { /* best-effort */ }
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    onLoaded()
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    onFailed()
                }
            }
        }
        adView.loadAd(buildAdRequest())
        return adView
    }

    fun destroyBanner(adView: AdView?) { adView?.destroy() }

    // --- Contextual trigger wrappers (preserve existing call sites)
    /**
     * Record an interstitial-triggering event. This will show an interstitial every
     * INTERSTITIAL_AD_EVERY events.
     */
    private fun recordInterstitialEventAndMaybeShow(onDismiss: () -> Unit = {}) {
        interstitialEventCount++
        if (interstitialEventCount % INTERSTITIAL_AD_EVERY == 0) showInterstitial(onDismiss)
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
        // Clean finished is a natural transition; show an interstitial immediately if available.
        showInterstitial(onDismiss)
    }

    fun onCleanFinishedCancel(onDismiss: () -> Unit = {}) {
        // Consider not showing on cancel; keep unified behavior but allow suppression here if desired
        recordInterstitialEventAndMaybeShow(onDismiss)
    }

    fun onUninstallConfirmed(onDismiss: () -> Unit = {}) {
        recordInterstitialEventAndMaybeShow(onDismiss)
    }
}
