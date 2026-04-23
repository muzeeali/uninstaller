# Ad Policy Audit — Uninstaller App

Generated: 2026-04-21

This file lists all ad placements found in the codebase, potential Google AdMob/Google Ads policy issues, references, and recommended fixes.

| Ad type | Ad unit ID | Placement / callsites (file) | Potential policy issues (summary) | References | Recommended fix |
|---|---:|---|---|---|---|
| App Open (full‑screen) | ca-app-pub-6425054459696619/3748084648 | `AdManager.loadAppOpen()` / `showAppOpenAdIfAvailable()` — called from `MainActivity` lifecycle observer and also in `onCreate` | Risk of showing full‑screen ad immediately on launch/resume and possibly twice (onCreate + onStart) → poor UX and may run afoul of guidance to avoid interrupting initial app flow or chaining multiple full‑screen ads | App Open / Quick‑start guidance: https://developers.google.com/admob/android/quick-start ; Interstitial best practices: https://developers.google.com/admob/android/interstitial | Only show App Open once per cold start; add cooldown/session caps; avoid calling both in `onCreate` and `onStart`; ensure it never immediately precedes/follows another full‑screen ad |
| Interstitial (full‑screen) | ca-app-pub-6425054459696619/8013363790 | `AdManager.loadInterstitial()` / `showInterstitial()` — triggers: home refresh, history refresh, select all, selection≥3, tab switch, enter settings, navigate to home, after uninstall, CleanFinished, etc. (see `AdManager.kt` and `MainActivity.kt`) | Excessive frequency and many small-action triggers increase accidental/annoying impressions; potential for back‑to‑back full‑screen ads (e.g., App Open → Interstitial or Rewarded → Interstitial) — violates best practices about frequency and natural transitions; risk of accidental clicks if shown during or immediately after system dialogs | Interstitial best practices & frequency guidance: https://developers.google.com/admob/android/interstitial ; policy guidance: https://support.google.com/google-ads/ | Implement global frequency caps (per session and per minute), ensure interstitials appear only at natural transition points, prevent back‑to‑back full‑screen ads (enforce minimum gap and single active full‑screen ad), and avoid showing on critical system flows (uninstall / install prompts) |
| Rewarded (video) | ca-app-pub-6425054459696619/1377906329 | `AdManager.loadRewarded()` / `showRewarded()` — used to unlock bulk uninstall / bypass cooldowns (HomeScreen bulk flows, BulkActionBar) | Reward semantics must be clear and rewards reliably granted on completion; must not be used to force users into viewing other ads or to misrepresent reward; also need user consent when using personalized ads | Rewarded docs: https://developers.google.com/admob/android/rewarded-interstitial ; Rewarded best practices: https://developers.google.com/admob/android/quick-start | Ensure reward is always granted inside OnUserEarnedReward; present clear opt‑in UI explaining reward; avoid requiring ad watch for core app functionality without alternative; integrate consent (UMP) if personalized ads used |
| Banner (adaptive) | ca-app-pub-6425054459696619/9326445462 | `BannerAdView()` composable used across Home, History, CleanFinished, DeepCleanProgress, Settings (`MainActivity.kt`) — anchored bottom center | Potential accidental clicks if banner overlaps tappable UI or is placed too close to important interactive elements (buttons, nav bars); multiple screens show banner at bottom — must avoid overlap and accidental clicks | Banner placement guidance: https://developers.google.com/admob/android/banner ; Ad placement best practices: https://developers.google.com/admob/android/interstitial | Ensure banners do not overlap or obscure interactive UI, reserve safe bottom area, confirm banner does not appear on screens where system UI or important buttons are at the same position, and test with Ad Inspector to verify no accidental tap areas |
<!-- Test ad unit IDs removed from source -->
| App‑level AdMob App ID | ca-app-pub-6425054459696619~6276047191 | `AndroidManifest.xml` meta‑data | OK — required. No violation, but be mindful of privacy/consent requirements | AdMob setup docs: https://developers.google.com/admob/android/quick-start | Keep App ID in manifest; ensure privacy disclosures and consent flows are implemented when required (EU/GLBA/COPPA etc.) |


## Evidence & Notes

- Interstitial frequency / user experience: Google docs advise avoiding excessive frequency and showing interstitials only at natural transition points. See Interstitial best practices: https://developers.google.com/admob/android/interstitial
- Rewarded & Rewarded Interstitial behavior: docs require rewards be granted reliably and shown in an opt‑in context (rewarded) or with intro/opt‑out (rewarded interstitial). See: https://developers.google.com/admob/android/rewarded-interstitial
- Banner placement / accidental clicks: banners must not obscure UI or produce accidental clicks; follow banner placement guide: https://developers.google.com/admob/android/banner


## Recommended next steps

1. Implement a conservative global cooldown to prevent back‑to‑back full‑screen ads.
2. Ensure App Open is shown only once per cold start and not chained with interstitials.
3. Add explicit user-facing copy/opt-in for rewarded ads and ensure reward delivery logic is bulletproof.
4. Run Ad Inspector and manual UX tests (include device rotations and system dialogs) to verify no accidental taps.
5. Integrate the User Messaging Platform (UMP) for consent where required.


---

Audit performed by automated code scan + Google Mobile Ads docs (2026-04-21).

## Changes applied in repository (resolved issues)

Updated: 2026-04-21

The following changes have been implemented in the codebase to address items from this audit. These are recorded here so reviewers and QA know what was fixed and where to verify.

- Centralized ad lifecycle into `app/src/main/java/.../AdManager.kt` and replaced multiple ad helpers with a single manager. This centralization reduces risk of inconsistent behavior across screens.

- Unified interstitial counting: replaced per-screen/context counters with a single universal `interstitialEventCount` so all screens share the same event tally and interstitial cadence is consistent (default: show every 3 events). See `AdManager.recordInterstitialEventAndMaybeShow()`.

- Reintroduced contextual wrapper methods (`onHomeRefreshTapped`, `onHistoryRefreshTapped`, `onSelectionChanged`, `onSelectAll`, `onTabSwitched`, `onEnteredSettings`, `onNavigatedToHome`) that call the centralized counter and obey global cooldown/cap rules. This preserves existing call sites while enforcing unified policy.

- Global cooldowns and caps added: minimum 90s between full‑screen ads and a sliding-window cap (3 interstitials per 10 minutes). These are enforced inside `AdManager` to prevent back‑to‑back full‑screen ads.

- Banner creation/destroy moved to centralized helpers: `AdManager.createAdaptiveBanner(context)` and `AdManager.destroyBanner(adView)`. All existing banner callsites were updated to use these helpers.

- Removed bypass ability: `AdManager.showInterstitial(forceAlways = ...)` no longer bypasses cooldowns — all interstitials now respect global cooldown/cap rules enforced in `AdManager`.

Notes / current status (what was changed and what remains)

- UMP consent flow: IMPLEMENTED (best-effort). The repo now includes a consent request flow in `AdManager.initialize()` that attempts to load/show the UMP consent form and sets ad personalization flags. The UMP SDK was added to `app/build.gradle.kts`.
-- Test ad unit IDs in source: REMOVED. `AdManager` no longer contains hard-coded test ad unit IDs; use Ad Inspector or configure test devices via the Google Mobile Ads SDK during development.
- Google Mobile Ads SDK: UPDATED to a recent stable release (`com.google.android.gms:play-services-ads:25.2.0`) in `app/build.gradle.kts`.
- Bypass removal: The `forceAlways` bypass was removed from callsites and the `showInterstitial` signature was simplified — all interstitials now respect the centralized cooldowns/caps.
- App version: BUMPED to `versionName=1.0.7`, `versionCode=8`.

Remaining high-priority actions (manual / dashboard)

- Run Ad Inspector and capture offending creatives (screenshots, ad unit ID, mediation chain, Ad Inspector trace). This must be done on a device/emulator using the running app.
- Block offending ad units or networks in the AdMob dashboard / mediation settings once the bad creative/source is identified.
- Collect logs/evidence and submit an appeal/report to Google (include Ad Inspector trace, screenshots, timestamps, reproduction steps).
- Manual runtime QA: exercise noisy buttons and flows (cold start, uninstall flow, extract, CleanFinished) to ensure interstitial cadence is now reasonable.
- Implement additional runtime safeguards if needed (ad content filters, block specific categories, set maximum ad content rating in AdMob).

To verify the changes, inspect `app/src/main/java/com/zeetech/uninstaller/bulk/apk/extractor/cleaner/AdManager.kt` and `MainActivity.kt` (banner composable callsites). Run a Gradle build and exercise the app on a device/emulator to capture Ad Inspector traces and confirm runtime behavior.

