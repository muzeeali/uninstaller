import os
import re

file_path = 'app/src/main/java/com/zeetech/uninstaller/bulk/apk/extractor/cleaner/MainActivity.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    text = f.read()

# Remove imports
text = re.sub(r'import com\.google\.android\.gms\.ads.*?\n', '', text)
text = re.sub(r'import androidx\.compose\.ui\.viewinterop\.AndroidView\n', '', text)

# Replace simple AdManager calls
text = re.sub(r'AdManager\.initialize\(this\)\n\s*', '', text)
text = re.sub(r'AdManager\.showAppOpenAdIfAvailable\(\)\n\s*', '', text)
text = re.sub(r'// Refresh AdManager\'s weak reference.*?\n\s*AdManager\.bindActivity\(this\)\n', '', text)
text = re.sub(r'AdManager\.bindActivity\(this\)\n\s*', '', text)
text = re.sub(r'AdManager\.onEnteredSettings\(\)\n\s*', '', text)
text = re.sub(r'AdManager\.onNavigatedToHome\(\)\n\s*', '', text)
text = re.sub(r'AdManager\.onHomeRefreshTapped\(\)\n\s*', '', text)
text = re.sub(r'AdManager\.onHistoryRefreshTapped\(\)\n\s*', '', text)
text = re.sub(r'AdManager\.onNavigateToHistory\(\)\n\s*', '', text)

# Block AdManager.showAppOpenAdIfAvailable { ... }
app_open_ad = """                // Wait for the ad to be dismissed or failed before showing the rating prompt
                AdManager.showAppOpenAdIfAvailable {
                    if (!AdManager.appLifecycleFirstStart) {
                        showForegroundRatingPrompt()
                    }
                    AdManager.appLifecycleFirstStart = false
                }"""
replacement_app_open = """                // Wait for the ad to be dismissed or failed before showing the rating prompt
                if (!appLifecycleFirstStart) {
                    showForegroundRatingPrompt()
                }
                appLifecycleFirstStart = false"""
text = text.replace(app_open_ad, replacement_app_open)

# Add private var appLifecycleFirstStart = true at the beginning of MainActivity
first_start_dec = "class MainActivity : ComponentActivity() {\n    private var appLifecycleFirstStart = true\n"
text = text.replace("class MainActivity : ComponentActivity() {", first_start_dec)

# Block AdManager.onUninstallConfirmed(onDismiss = { ... })
uninstall_conf = """                    AdManager.onUninstallConfirmed(onDismiss = {
                        viewModel.loadApps()
                    })"""
text = text.replace(uninstall_conf, "                    viewModel.loadApps()")

# Block AdManager.showInterstitial(onDismiss = { activity?.showActionRatingPrompt() })
interstitial_1 = """                        AdManager.showInterstitial(onDismiss = {
                            activity?.showActionRatingPrompt()
                        })"""
text = text.replace(interstitial_1, "                        activity?.showActionRatingPrompt()")

interstitial_2 = """                        AdManager.showInterstitial(onDismiss = {
                            onDone()
                        })"""
text = text.replace(interstitial_2, "                        onDone()")

# Block AdManager.onExtractTapped
extract_tapped = """                                AdManager.onExtractTapped(onDismiss = {
                                    activity?.showActionRatingPrompt()
                                })"""
text = text.replace(extract_tapped, "                                activity?.showActionRatingPrompt()")

# Block AdManager.showRewarded
show_rewarded = """                                if (AdManager.isRewardedReady()) {
                                    AdManager.showRewarded(
                                        onRewardEarned = action,
                                        onDismiss = {}
                                    )
                                } else {
                                    action()
                                }"""
text = text.replace(show_rewarded, "                                action()")

show_rewarded_2 = """                            if (AdManager.isRewardedReady() && !isBulkAdUnlocked) {
                                AdManager.showRewarded(onRewardEarned = action, onDismiss = {})
                            } else {
                                action()
                            }"""
text = text.replace(show_rewarded_2, "                            action()")

# Remove HomeScreen ad params
home_screen_params = """                            isBulkAdUnlocked = AdManager.rewardedUnlockActive,
                            onTabSwitched = { AdManager.onTabSwitched() },
                            onSelectAll = { AdManager.onSelectAll() },
                            onSelectionChanged = { count -> AdManager.onSelectionChanged(count) }"""
home_screen_replacement = """                            onTabSwitched = { },
                            onSelectAll = { },
                            onSelectionChanged = { count -> }"""
text = text.replace(home_screen_params, home_screen_replacement)

text = re.sub(r'val isRewardedReady by AdManager\.isRewardedReadyFlow\.collectAsState\(\)\n\s*', '', text)

# Remove isBulkAdUnlocked param in HomeScreen signature
text = re.sub(r'\s*isBulkAdUnlocked: Boolean,', '', text)

# Fix BulkActionBar isAdUnlocked param
bulk_action = """                    isAdUnlocked = if (selectedApps.size == 1) !isRewardedReady || isBulkAdUnlocked else !isRewardedReady,"""
text = text.replace(bulk_action, "                    isAdUnlocked = true,")

# Remove BannerAdView calls
banner_box_1 = """        // Banner ad anchored to absolute bottom (Matching Home layout)
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            BannerAdView()
        }"""
text = text.replace(banner_box_1, "")

banner_box_2 = """        // Banner ad anchored to absolute bottom
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            BannerAdView()
        }"""
text = text.replace(banner_box_2, "")

banner_box_3 = """            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                BannerAdView()
            }"""
text = text.replace(banner_box_3, "")

# the definition itself
banner_def = """// ─── Reusable Banner Ad Composable ──────────────────────────────────────────
@Composable
fun BannerAdView() {
    // STUBBED OUT: Banner ad display removed but structure preserved
    // No space allocated for ads
}"""
text = text.replace(banner_def, "")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(text)
print("Done refactoring MainActivity.kt")
