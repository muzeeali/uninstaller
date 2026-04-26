package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import java.util.Date

const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-6425054459696619/3748084648"
const val BANNER_AD_UNIT_ID = "ca-app-pub-6425054459696619/9326445462"
const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-6425054459696619/8013363790"
const val REWARDED_AD_UNIT_ID = "ca-app-pub-6425054459696619/1377906329"

class AdManager(private val application: Application, private val activity: Activity) {

    // Remote Config flags (defaults to true until fetch completes)
    var adsEnabled = true
    var bannerEnabled = true
    var rewardedEnabled = true
    var appOpenEnabled = true

    val appOpenAdManager = AppOpenAdManager(application)
    val rewardedAdManager = RewardedAdManager(application)

    fun initialize(onReady: () -> Unit) {
        // 1. UMP Consent (GDPR)
        val params = ConsentRequestParameters.Builder().build()
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        
        consentInformation.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    // Consent gathering failed or is not required.
                    if (formError != null) {
                        android.util.Log.e("AdManager", "Consent form error: ${formError.errorCode} - ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        initMobileAds(onReady)
                    }
                }
            },
            { requestConsentError ->
                android.util.Log.e("AdManager", "Consent info update error: ${requestConsentError.errorCode} - ${requestConsentError.message}")
                // On error, proceed anyway if we already have consent from a previous session
                if (consentInformation.canRequestAds()) {
                    initMobileAds(onReady)
                } else {
                     onReady()
                }
            }
        )

        // Check if you can initialize the Google Mobile Ads SDK in parallel
        // while checking for new consent information. Consent obtained in
        // the previous session can be used to request ads.
        if (consentInformation.canRequestAds()) {
            initMobileAds(onReady)
        }
    }

    private var isMobileAdsInitializeCalled = false

    private fun initMobileAds(onReady: () -> Unit) {
        if (isMobileAdsInitializeCalled) return
        isMobileAdsInitializeCalled = true

        MobileAds.initialize(activity) {
            fetchRemoteConfig(onReady)
        }
    }

    private fun fetchRemoteConfig(onReady: () -> Unit) {
        val remoteConfig = Firebase.remoteConfig
        remoteConfig.setConfigSettingsAsync(
            remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 }
        )
        remoteConfig.fetchAndActivate().addOnCompleteListener {
            adsEnabled = remoteConfig.getBoolean("ads_enabled")
            bannerEnabled = adsEnabled && remoteConfig.getBoolean("banner_ads_enabled")
            rewardedEnabled = adsEnabled && remoteConfig.getBoolean("rewarded_ads_enabled")
            appOpenEnabled = adsEnabled && remoteConfig.getBoolean("app_open_ads_enabled")

            if (appOpenEnabled) appOpenAdManager.loadAd()
            if (rewardedEnabled) rewardedAdManager.loadAd()
            onReady()
        }
    }
}

class AppOpenAdManager(private val application: Application) {

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTime: Long = 0L

    fun loadAd() {
        if (isLoadingAd || isAdAvailable()) return
        isLoadingAd = true
        AppOpenAd.load(
            application,
            APP_OPEN_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                    loadTime = Date().time
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                }
            }
        )
    }

    private fun wasLoadTimeLessThanNHoursAgo(numHours: Long): Boolean {
        val dateDifference = Date().time - loadTime
        val numMilliSecondsPerHour: Long = 3600000
        return dateDifference < numMilliSecondsPerHour * numHours
    }

    private fun isAdAvailable(): Boolean {
        // Ad expires after 4 hours
        return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(4)
    }

    fun showAdIfAvailable(activity: Activity) {
        if (isShowingAd) return
        
        if (!isAdAvailable()) {
            loadAd() // Pre-load for next time
            return
        }
        
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd() // Pre-load next one
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                isShowingAd = false
                loadAd()
            }
            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
            }
        }
        isShowingAd = true
        appOpenAd?.show(activity)
    }
}

class RewardedAdManager(private val application: Application) {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun loadAd() {
        if (isLoading || rewardedAd != null) return
        isLoading = true
        RewardedAd.load(
            application,
            REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                }
            }
        )
    }

    fun showRewardedAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onDismissed: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            // Ad not ready — grant reward anyway so user isn't blocked
            onRewarded()
            loadAd()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadAd()
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                loadAd()
                onRewarded() // Fail-open: don't block user
            }
        }
        ad.show(activity) { _ -> onRewarded() } // onUserEarnedReward
    }
}

@Composable
fun BannerAdView(adUnitId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            AdView(context).apply {
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        context, 
                        getScreenWidthDp(context)
                    )
                )
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

private fun getScreenWidthDp(context: Context): Int {
    val displayMetrics = context.resources.displayMetrics
    return (displayMetrics.widthPixels / displayMetrics.density).toInt()
}
