# Aqua AI R8 / ProGuard Hardening Rules
# Preserves reflection, JNI, Serialization, Services, Workers, and JS Bridges while obfuscating internals and stripping verbose debug logs.

-keepattributes Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,*Annotation*

# Strip verbose/debug logging in R8 release builds to prevent accidental telemetry leakage
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 1. WebView @JavascriptInterface Bridges (AquaLegalPortalAuthBridge)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.browser.AquaLegalPortalAuthBridge { *; }

# 2. Android Services, Receivers, Activity, and WorkManager Workers
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.core.drive.AquaDriveSyncWorker { *; }
-keep class com.example.core.automation.AquaAutomationWorker { *; }
-keep class com.example.device.AquaAccessibilityService { *; }
-keep class com.example.device.AquaNotificationListenerService { *; }
-keep class com.example.voice.AquaWakeWordService { *; }
-keep class com.example.voice.AquaBootReceiver { *; }

# 3. Encrypted Room SSOT & SQLCipher Native JNI
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.sqlcipher.**
-dontwarn net.zetetic.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class com.example.core.database.** { *; }
-keep class com.example.data.local.AquaCacheDatabase { *; }
-keep class com.example.data.local.AquaCacheDatabase_Impl { *; }

# 4. Firebase Firestore, Kotlin Serialization & Moshi Data Models
-keepclassmembers class com.example.data.model.** { *; }
-keep class com.example.data.model.** { *; }
-keep class com.example.data.repository.DriveHistorySnapshot { *; }
-keep class com.example.data.repository.DriveConversationSnapshot { *; }
-keep class com.example.data.repository.DriveMessageSnapshot { *; }
-keep class com.example.data.repository.DriveMemorySnapshot { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# 5. Non-Custodial Multichain Wallet Cryptography (BouncyCastle / BIP-39 / Secp256k1 / Ed25519)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.example.wallet.crypto.** { *; }
-keep class com.example.wallet.core.** { *; }
-keep class com.example.wallet.security.** { *; }

# 6. Core Security & Agent Enums / State Machines
-keep class com.example.core.security.** { *; }
-keep class com.example.core.agent.** { *; }
-keep class com.example.core.workspace.** { *; }
-keep class com.example.core.connectors.** { *; }
