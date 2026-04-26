# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Preservation of line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable

# Preserve all Activities, Services, and Providers from the Manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep the ViewModel to prevent reflection errors
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModel { *; }

# WorkManager stability: Keep Workers for reflection-based instantiation
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# AndroidX Splash Screen library
-keep class androidx.core.splashscreen.** { *; }

# Preserve custom AppUiState and AppInfo data classes if they are used in any reflection/serialization
-keep class com.zeetech.uninstaller.bulk.apk.extractor.cleaner.AppUiState { *; }
-keep class com.zeetech.uninstaller.bulk.apk.extractor.cleaner.AppUiState$* { *; }
-keep class com.zeetech.uninstaller.bulk.apk.extractor.cleaner.AppInfo { *; }

# Coil image loading library
-keep class coil.** { *; }
-keep interface coil.** { *; }

# WorkManager internal components (Needed for initialization)
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkManagerInitializer { *; }

# Room components (WorkManager depends on Room)
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Entity
-keep class * extends androidx.room.Dao
-dontwarn androidx.work.impl.**
-dontwarn androidx.room.**

# Google Mobile Ads (AdMob) — required to prevent stripping in release builds
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.ump.**