package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
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
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPremium = MutableStateFlow(false)
    val isPremium = _isPremium.asStateFlow()

    // Placeholder Product IDs
    const val PRODUCT_MONTHLY = "premium_monthly"
    const val PRODUCT_YEARLY = "premium_yearly"
    const val PRODUCT_LIFETIME = "premium_lifetime"

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
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

        connectToPlayBilling()
    }

    private fun connectToPlayBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup finished")
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected")
                // Try to reconnect? Usually handled by activity lifecycle calls
            }
        })
    }

    fun queryPurchases() {
        if (!billingClient.isReady) return

        // Query Subscriptions
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            }
        }

        // Query One-time (Lifetime)
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            }
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
        if (_isPremium.value != finalStatus) {
            _isPremium.value = finalStatus
            // Save to cache
            val ctx = AdManager.getAppContext() // AdManager has it, or we can pass it
            ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_IS_PREMIUM, finalStatus)?.apply()
            
            // Update Firebase User Property
            firebaseAnalytics.setUserProperty("premium_status", if (finalStatus) "premium" else "basic")
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

        billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList[0]
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
        queryPurchases()
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
