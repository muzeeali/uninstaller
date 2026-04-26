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
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

private const val TAG = "AdManager"

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
    private const val AD_UNIT_APP_OPEN: String = "ca-app-pub-6425054459696619/3748084648"
    private const val AD_UNIT_INTERSTITIAL: String = "ca-app-pub-6425054459696619/8013363790"
    private const val AD_UNIT_REWARDED: String = "ca-app-pub-6425054459696619/1377906329"
    private const val AD_UNIT_BANNER: String = "ca-app-pub-6425054459696619/9326445462"

    // Consent / personalization flags
    private var isPersonalizedAdsAllowed: Boolean = true

    // Single-active ad lock
    private var isAdShowing = false

    // AppOpen
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdLoading = false
    private var appOpenShownThisColdStart = false
    private var pendingShowAppOpenOnLoad = false
    // If true, allow showing App Open on every foreground resume (not just once per cold start)
    // Set to false to avoid showing App Open on every resume; prefer cold-start only.
    var appOpenShowEveryResume: Boolean = true
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

    // Remote Config flags
    private var adsEnabled = true
    private var bannerAdsEnabled = true
    private var interstitialAdsEnabled = true
    private var rewardedAdsEnabled = true
    private var appOpenAdsEnabled = true
    private var alwaysOnInterstitialEnabled = true
    private var counterInterstitialEnabled = true
    private var interstitialFrequency = 3

    // Shared Interstitial Counter (Category 2)
    private var eventCount = 0

    // Selection cycle guard (resets when user deselects all)
    private var selectionCycleTriggered = false

    // Initialize and preload
    fun initialize(activity: Activity) {
        appCtx = activity.applicationContext
        bindActivity(activity)
        
        // Initialize Remote Config
        fetchRemoteConfig()

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

    private fun fetchRemoteConfig() {
        try {
            val remoteConfig = com.google.firebase.Firebase.remoteConfig
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3600
            }
            remoteConfig.setConfigSettingsAsync(configSettings)
            remoteConfig.setDefaultsAsync(mapOf(
                "ads_enabled" to true,
                "banner_ads_enabled" to true,
                "interstitial_ads_enabled" to true,
                "rewarded_ads_enabled" to true,
                "app_open_ads_enabled" to true,
                "always_on_interstitial_enabled" to true,
                "counter_interstitial_enabled" to true,
                "interstitial_counter_frequency" to 3
            ))

            remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    adsEnabled = remoteConfig.getBoolean("ads_enabled")
                    bannerAdsEnabled = remoteConfig.getBoolean("banner_ads_enabled")
                    interstitialAdsEnabled = remoteConfig.getBoolean("interstitial_ads_enabled")
                    rewardedAdsEnabled = remoteConfig.getBoolean("rewarded_ads_enabled")
                    appOpenAdsEnabled = remoteConfig.getBoolean("app_open_ads_enabled")
                    alwaysOnInterstitialEnabled = remoteConfig.getBoolean("always_on_interstitial_enabled")
                    counterInterstitialEnabled = remoteConfig.getBoolean("counter_interstitial_enabled")
                    interstitialFrequency = remoteConfig.getLong("interstitial_counter_frequency").toInt().coerceAtLeast(1)
                    Logger.d(TAG, "Remote Config updated: adsEnabled=$adsEnabled, freq=$interstitialFrequency")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Remote Config initialization failed: ${e.message}")
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
        if (!adsEnabled) return false
        if (DEBUG_SUPPRESS_ADS) return false
        if (isAdShowing) return false
        
        return true
    }

    // --- Full-screen ad rate limiting
    private var lastFullScreenAdTimestamp: Long = 0L
    private val fullScreenAdTimestamps: MutableList<Long> = mutableListOf()
    // Rate limiting removed per user request for "no requirements of 90 sec or any other"

    private fun recordFullScreenAdShown() {
        val now = System.currentTimeMillis()
        lastFullScreenAdTimestamp = now
        fullScreenAdTimestamps.add(now)
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
        if (!adsEnabled || !appOpenAdsEnabled) return
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
        if (!adsEnabled || !appOpenAdsEnabled) { onDismiss(); return }
        if (DEBUG_SUPPRESS_ADS) { onDismiss(); return }
        if (isAdShowing) { onDismiss(); return }
        
        val activity = currentActivity() ?: run { onDismiss(); return }
        val ad = appOpenAd ?: run { onDismiss(); return }

        isAdShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // Record timestamp immediately when the ad becomes visible so rate
                // limits account for actual impressions rather than dismissals.
                recordFullScreenAdShown()
            }
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
        if (!adsEnabled || !interstitialAdsEnabled) return
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

    fun showInterstitial(onDismiss: () -> Unit = {}, ignoreCooldown: Boolean = false) {
        if (!adsEnabled || !interstitialAdsEnabled) { onDismiss(); return }
        if (DEBUG_SUPPRESS_ADS) { onDismiss(); return }
        val activity = currentActivity() ?: run { onDismiss(); return }

        // Category 2 (Counter) ads must respect global cooldowns/caps.
        // Category 1 (Always-On) bypasses the 90s cooldown but still respects the "Is Ad Showing" lock.
        if (!ignoreCooldown && !canShowFullScreen()) {
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
            override fun onAdShowedFullScreenContent() {
                recordFullScreenAdShown()
            }
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
        if (!adsEnabled || !rewardedAdsEnabled) return
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
        if (!adsEnabled || !rewardedAdsEnabled) {
            onRewardEarned()
            return
        }
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
            override fun onAdShowedFullScreenContent() {
                recordFullScreenAdShown()
            }
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
    ): AdView? {
        if (!adsEnabled || !bannerAdsEnabled) {
            onFailed()
            return null
        }
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

    // --- Category 1: Always-On Method
    fun showInterstitialAlwaysOn(onDismiss: () -> Unit = {}) {
        if (!adsEnabled || !interstitialAdsEnabled || !alwaysOnInterstitialEnabled) { onDismiss(); return }
        if (isAdShowing) { onDismiss(); return }
        // Show immediately — no cooldown for Always-On
        showInterstitial(onDismiss, ignoreCooldown = true)
    }

    // --- Category 2: Counter Method
    private fun recordCounterEvent(onDismiss: () -> Unit = {}) {
        if (!adsEnabled || !interstitialAdsEnabled || !counterInterstitialEnabled) {
            Logger.d(TAG, "Counter event ignored: adsEnabled=$adsEnabled, interstitialAdsEnabled=$interstitialAdsEnabled, counterInterstitialEnabled=$counterInterstitialEnabled")
            onDismiss()
            return
        }
        eventCount++
        val shouldShow = eventCount % interstitialFrequency == 0
        Logger.d(TAG, "Counter event #$eventCount (freq=$interstitialFrequency). shouldShow=$shouldShow")
        if (shouldShow && !isAdShowing) {
            showInterstitial(onDismiss)
        } else {
            if (isAdShowing) Logger.d(TAG, "Counter event suppressed: Ad is already showing")
            onDismiss()
        }
    }

    fun onHomeRefreshTapped() {
        recordCounterEvent()
    }

    fun onHistoryRefreshTapped() {
        // Only removing the interstitial counter call as per plan
    }

    fun onSelectionChanged(newCount: Int) {
        if (newCount == 0) selectionCycleTriggered = false
        if (newCount >= 3 && !selectionCycleTriggered) {
            selectionCycleTriggered = true
            recordCounterEvent()
        }
    }

    fun onSelectAll() {
        selectionCycleTriggered = true
        recordCounterEvent()
    }

    fun onTabSwitchedByClick() {
        recordCounterEvent()
    }

    fun onTabSwitchedBySwipe() {
        recordCounterEvent()
    }

    fun onEnteredSettings() {
        loadInterstitial()
    }

    fun onNavigatedHomeFromSettings() {
        recordCounterEvent()
    }

    fun onExtractApkSuccess(onDismiss: () -> Unit = {}) {
        recordCounterEvent(onDismiss)
    }

    fun onNavigateToHistory(onDismiss: () -> Unit = {}) {
        onDismiss()
    }

    fun onCleanFinishedDone(onDismiss: () -> Unit = {}) {
        showInterstitialAlwaysOn(onDismiss)
    }

    fun onOptimizedScreenDone(onDismiss: () -> Unit = {}) {
        recordCounterEvent(onDismiss)
    }

    fun onCleanFinishedCancel(onDismiss: () -> Unit = {}) {
        recordCounterEvent(onDismiss)
    }

    fun onSingleUninstallFromBar(onDismiss: () -> Unit = {}) {
        recordCounterEvent(onDismiss)
    }

    fun onUninstallConfirmed(onDismiss: () -> Unit = {}) {
        showInterstitialAlwaysOn(onDismiss)
    }

    fun onSwipeToRefresh() {
        recordCounterEvent()
    }
}
