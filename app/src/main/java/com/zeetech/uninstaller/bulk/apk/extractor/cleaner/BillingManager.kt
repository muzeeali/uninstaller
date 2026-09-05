package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

object BillingManager : PurchasesUpdatedListener {
    private const val TAG = "BillingManager"
    private const val PREFS_NAME = "surgical_uninstaller_prefs"
    private const val KEY_IS_PREMIUM = "is_premium"

    private lateinit var billingClient: BillingClient
    val isBillingReady: Boolean get() = ::billingClient.isInitialized && billingClient.isReady
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPremium = MutableStateFlow(false)
    val isPremium = _isPremium.asStateFlow()

    private val _productPrices = MutableStateFlow<Map<String, String>>(emptyMap())
    val productPrices = _productPrices.asStateFlow()

    // True once real prices have been fetched from the Play Billing client
    private val _pricesLoaded = MutableStateFlow(false)
    val pricesLoaded = _pricesLoaded.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring = _isRestoring.asStateFlow()

    // null = no pending message; non-null = show snackbar with this text
    private val _restoreResult = MutableStateFlow<String?>(null)
    val restoreResult = _restoreResult.asStateFlow()

    fun clearRestoreResult() { _restoreResult.value = null }

    // Production Product IDs
    const val PRODUCT_MONTHLY = "com.zeetech.uninstaller.monthly"
    const val PRODUCT_YEARLY = "com.zeetech.uninstaller.yearly"
    const val PRODUCT_LIFETIME = "com.zeetech.uninstaller.lifetime"

    var DEBUG_FORCE_PREMIUM = false
        set(value) {
            field = value
            if (value) _isPremium.value = true
        }

    fun initialize(context: Context) {
        firebaseAnalytics = Firebase.analytics
        
        // Load from cache first for immediate UI update
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isPremium.value = prefs.getBoolean(KEY_IS_PREMIUM, false) || DEBUG_FORCE_PREMIUM

        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()

        connectToPlayBilling()
        
        // Fetch Force Premium Flag
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                if (remoteConfig.getBoolean("force_premium_enabled")) {
                    updatePremiumStatus(true)
                }
            }
        }
    }

    private fun connectToPlayBilling() {
        scope.launch {
            kotlinx.coroutines.delay(10000)
            if (!_pricesLoaded.value) {
                Log.w(TAG, "Billing setup timed out. Force marking prices as loaded.")
                _pricesLoaded.value = true
            }
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup finished")
                    queryPurchases()
                    fetchProductDetails()
                } else {
                    Log.e(TAG, "Billing setup failed with response code: ${billingResult.responseCode}")
                    _pricesLoaded.value = true
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected — scheduling reconnect")
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    connectToPlayBilling()
                }
            }
        })
    }

    fun queryPurchases() {
        if (!billingClient.isReady) return

        // Collect both SUBS + INAPP results before calling updatePremiumStatus
        // to avoid race-condition where one query transiently revokes premium.
        var subsResult: List<Purchase>? = null
        var inappsResult: List<Purchase>? = null

        fun tryProcess() {
            val subs = subsResult ?: return   // wait until both are ready
            val inapps = inappsResult ?: return
            processPurchases(subs + inapps)
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            subsResult = if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList()
            tryProcess()
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            inappsResult = if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList()
            tryProcess()
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        var premiumActive = false
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                premiumActive = true
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            }
        }
        
        // Update state and cache
        updatePremiumStatus(premiumActive)
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            processPurchases(purchases)
            logPurchaseEvent(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User canceled purchase")
        }
    }

    private fun updatePremiumStatus(active: Boolean) {
        val finalStatus = active || DEBUG_FORCE_PREMIUM
        _isPremium.value = finalStatus

        // Always persist so the cache is correct on every evaluation,
        // including first-run where the old value equals the new value.
        val ctx = AdManager.getAppContext()
        ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_IS_PREMIUM, finalStatus)?.apply()

        // Update Firebase User Property
        firebaseAnalytics.setUserProperty("premium_status", if (finalStatus) "premium" else "basic")
    }

    fun fetchProductDetails() {
        val subProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_MONTHLY).setProductType(BillingClient.ProductType.SUBS).build(),
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_YEARLY).setProductType(BillingClient.ProductType.SUBS).build()
        )
        val inAppProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_LIFETIME).setProductType(BillingClient.ProductType.INAPP).build()
        )

        val newPrices = mutableMapOf<String, String>()
        var completedQueries = 0

        fun checkCompletion() {
            completedQueries++
            if (completedQueries == 2) {
                _productPrices.value = _productPrices.value + newPrices
                _pricesLoaded.value = true   // signal UI: real localized prices are ready
            }
        }

        // 1. Query SUBS
        val subsParams = QueryProductDetailsParams.newBuilder().setProductList(subProducts).build()
        billingClient.queryProductDetailsAsync(subsParams) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.forEach { details: ProductDetails ->
                    // Prefer the base-plan offer (skip free-trial phases that have priceAmountMicros == 0)
                    val basePlanOffer = details.subscriptionOfferDetails
                        ?.firstOrNull { offer ->
                            offer.pricingPhases.pricingPhaseList.none { phase -> phase.priceAmountMicros == 0L }
                        }
                        ?: details.subscriptionOfferDetails?.firstOrNull()
                    val price = basePlanOffer
                        ?.pricingPhases?.pricingPhaseList
                        ?.firstOrNull { phase -> phase.priceAmountMicros > 0L }
                        ?.formattedPrice
                    price?.let { p -> newPrices[details.productId] = p }
                }
            } else {
                Log.e(TAG, "Failed to fetch subscriptions details: ${result.responseCode}, ${result.debugMessage}")
            }
            checkCompletion()
        }

        // 2. Query INAPP
        val inAppParams = QueryProductDetailsParams.newBuilder().setProductList(inAppProducts).build()
        billingClient.queryProductDetailsAsync(inAppParams) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.forEach { details: ProductDetails ->
                    val price = details.oneTimePurchaseOfferDetails?.formattedPrice
                    price?.let { p -> newPrices[details.productId] = p }
                }
            } else {
                Log.e(TAG, "Failed to fetch in-app details: ${result.responseCode}, ${result.debugMessage}")
            }
            checkCompletion()
        }
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val productType = if (productId == PRODUCT_LIFETIME) BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS
        
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && queryResult.productDetailsList.isNotEmpty()) {
                val productDetails = queryResult.productDetailsList[0]
                val offerToken = productDetails.subscriptionOfferDetails?.get(0)?.offerToken ?: ""
                
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .apply { if (productType == BillingClient.ProductType.SUBS) setOfferToken(offerToken) }
                            .build()
                    ))
                    .build()
                
                billingClient.launchBillingFlow(activity, flowParams)
            }
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady || _isRestoring.value) return
        _isRestoring.value = true
        var completedQueries = 0
        var foundPremium = false

        fun onQueryComplete() {
            completedQueries++
            if (completedQueries == 2) {
                _isRestoring.value = false
                _restoreResult.value = if (foundPremium) {
                    "✓ Purchase restored successfully!"
                } else {
                    "No active purchases found to restore."
                }
            }
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            // Only count as "found" if there is at least one truly PURCHASED item
            if (result.responseCode == BillingClient.BillingResponseCode.OK &&
                purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                processPurchases(purchases)
                foundPremium = true
            }
            onQueryComplete()
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK &&
                purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                processPurchases(purchases)
                foundPremium = true
            }
            onQueryComplete()
        }
    }

    private fun logPurchaseEvent(purchases: List<Purchase>) {
        for (purchase in purchases) {
            val bundle = android.os.Bundle().apply {
                putString(FirebaseAnalytics.Param.TRANSACTION_ID, purchase.orderId)
                putString(FirebaseAnalytics.Param.ITEM_ID, purchase.products.joinToString())
                putString(FirebaseAnalytics.Param.VALUE, "0.0") // Play Billing returns localized price separately
            }
            firebaseAnalytics.logEvent(FirebaseAnalytics.Event.PURCHASE, bundle)
        }
    }
}
