# Uninstaller: APK Extractor & Surgical Cleaner

A powerful, premium-grade Android utility designed for efficiency, performance, and modern aesthetics. Bulk uninstall apps, extract APKs (including App Bundles), and perform deep surgical cleaning with a state-of-the-art Jetpack Compose interface.

## 🚀 Key Features

- **Bulk Uninstaller:** Parallelized intent handling for fast, reliable multi-app removal.
- **APK Extractor:** Backup installed apps (Base and Split APKs) directly to your local storage.
- **Surgical Cleaner:** Advanced scanning algorithm to identify and purge deep-seated junk and leftover app data.
- **Modern UI/UX:** Premium design with glassmorphism effects, smooth animations, and a dynamic compact TopBar optimized for narrow mobile screens.
- **Adaptive Layouts:** Fully optimized for both Portrait and Landscape orientations with intelligent column-weighting and scaled UI elements.
- **Storage Analytics:** Real-time visual feedback on storage consumption and cleanup impact.

## 🛠️ Technical Specifications

- **Version:** `1.1.0` (Build 9)
- **Framework:** 100% Jetpack Compose / Kotlin Coroutines
- **Monetization:** Optimized AdMob integration featuring:
    - **Skip-1 App Open Logic:** Intelligent ad-display frequency to balance UX and revenue.
    - **Category-Based Interstitials:** Policy-compliant triggers based on user action thresholds (e.g., 3 uninstalls or 1 refresh).
    - **Policy Compliance:** Built-in safeguards against "ads on negative actions" and deceptive triggers.
- **Min SDK:** API 24 (Android 7.0)
- **Target SDK:** API 35 (Android 15)

## 🎨 Design System

The application uses a custom-tailored design system focused on **Visual Excellence**:
- **Color Palette:** Sleek "LogoPurple" and "EmeraldGreen" curated for high contrast and premium feel.
- **Compact Header:** A customized `CenterAlignedTopAppBar` using `LocalMinimumInteractiveComponentEnforcement` to maximize horizontal space for screen titles.
- **Responsive Elements:** Icons and spacers scale dynamically based on device orientation and screen width.

## 📦 Building the Project

1. Clone the repository.
2. Open in **Android Studio Koala** or later.
3. Ensure you have the `google-services.json` file in the `app/` directory.
4. Run `./gradlew assembleDebug` for testing or `assembleRelease` for production.

## 🔒 Privacy & Compliance

- **No Data Collection:** The app operates strictly locally; no user data is uploaded to external servers.
- **Scoped Storage:** Uses the latest Android storage APIs for maximum security.
- **Play Console Ready:** All ad implementations and UI flows are audited for Google Play Policy compliance.
