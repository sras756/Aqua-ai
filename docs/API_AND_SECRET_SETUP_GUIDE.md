# Aqua AI — API, Firebase App Check & OAuth 2.0 Secret Setup Guide

---

## 1. Zero Embedded APK Secrets Policy

Aqua AI **never** embeds static Gemini API keys, OAuth client secrets, or private keys inside `AndroidManifest.xml`, Kotlin source files, or `strings.xml`.

- **Firebase AI Logic (`Firebase.ai`):** Authenticated via Firebase Project (`google-services.json`, `firebase_applet_config.xml`) + **Firebase App Check** (`PlayIntegrityAppCheckProviderFactory` in release; `DebugAppCheckProviderFactory` with `BuildConfig.FIREBASE_APPCHECK_DEBUG_TOKEN` in debug builds).
- **Secrets Panel / `.env` Injection:** Optional build-time variables such as `GOOGLE_DRIVE_API_KEY` or `FIREBASE_APPCHECK_DEBUG_TOKEN` are managed via AI Studio's **Secrets panel** (`.env`, excluded from VCS; placeholder documented in `.env.example`) and exposed strictly through `BuildConfig`. Never use `local.properties` for API keys.
- **User-Supplied Runtime Keys (`SecureConfigStore`):** Optional BYOK keys (custom Gemini AI Studio API key, ElevenLabs TTS key, GitHub Personal Access Token for Developer Mode, Google Drive API override) entered in Settings are encrypted at rest via `AES-256-GCM` backed by Android Keystore (`aqua_secure_config_prefs.xml`) and **excluded** from Android Auto Backup (`backup_rules.xml`, `data_extraction_rules.xml`) and Google Drive (`SecretExclusionFilter`).

---

## 2. Firebase & App Check Provisioning

1. **Configuration Files:** Ensure valid project credentials exist in:
   - `app/google-services.json` (matching `applicationId = "com.aistudio.aquaai.vqrzmn"`)
   - `app/src/main/res/values/firebase_applet_config.xml` (defines `default_web_client_id`, `firebase_database_url`, `firestore_database_id`, `gcm_defaultSenderId`, `google_app_id`, `project_id`).
2. **Release App Check (Play Integrity):**
   - Enable **Play Integrity API** in Google Cloud Console and link your Firebase Android app's **release SHA-256** signing fingerprint in Firebase Console → App Check.
3. **Debug App Check (Emulators / Local Development):**
   - Run debug build, inspect Logcat for `DebugAppCheckProvider`, register the debug UUID token in Firebase Console → App Check → Apps → Manage debug tokens, or supply `FIREBASE_APPCHECK_DEBUG_TOKEN` via the AI Studio Secrets panel.

---

## 3. Google OAuth 2.0 Setup (Credential Manager & Drive / Ecosystem Scopes)

Aqua AI separates authentication identity from modular API scopes:

### Step A: Register Android & Web OAuth Clients
In **Google Cloud Console → APIs & Services → Credentials**:
1. **Web Application OAuth Client:** Matches `default_web_client_id` in `firebase_applet_config.xml` (required by Jetpack `CredentialManager` `GetSignInWithGoogleOption` / Firebase Auth ID token exchange).
2. **Android OAuth Client:**
   - **Package name:** `com.aistudio.aquaai.vqrzmn`
   - **SHA-1 fingerprints:** Obtain from your debug and upload/release keystores:
     ```bash
     keytool -list -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android
     keytool -list -v -keystore /path/to/my-upload-key.jks -alias upload
     ```
   - In-app verification: Navigate to **Settings → Google Signature Readiness** (`GoogleSignatureVerifier`) to inspect the active APK's runtime SHA-1 / SHA-256 fingerprints.

### Step B: Isolated Least-Privilege Scopes
| Feature Module | Requested OAuth 2.0 Scope | Authorization Mechanism |
|---|---|---|
| **Sign-In Identity** | `openid`, `email`, `profile` | Jetpack `CredentialManager` (`GetSignInWithGoogleOption`) |
| **Drive Vault & Continuity** | `https://www.googleapis.com/auth/drive.file`, `https://www.googleapis.com/auth/drive.appdata` | `Identity.getAuthorizationClient(context)` on user opt-in |
| **Connector: Gmail (Opt-In)** | `https://www.googleapis.com/auth/gmail.readonly` | `GoogleConnectorManager` independent toggle |
| **Connector: Calendar (Opt-In)** | `https://www.googleapis.com/auth/calendar.readonly` | `GoogleConnectorManager` independent toggle |
| **Connector: Contacts (Opt-In)** | `https://www.googleapis.com/auth/contacts.readonly` | `GoogleConnectorManager` independent toggle |
| **Connector: Photos (Opt-In)** | `https://www.googleapis.com/auth/photoslibrary.readonly` | `GoogleConnectorManager` independent toggle |

> **Isolation Invariant:** Signing into Aqua AI with Google **never** automatically authorizes Drive, Gmail, Calendar, Contacts, or Photos. Each connector requests only its single scope when toggled on and supports one-tap OAuth token revocation via `https://oauth2.googleapis.com/revoke`.

---

## 4. Release Build & Signing Deployment Instructions

`app/build.gradle.kts` preserves the AI Studio `release` and `debugConfig` signing configs. When exporting a signed release APK/AAB:
1. Configure `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` in your CI / AI Studio environment.
2. `app/proguard-rules.pro` is hardened with explicit keep rules for SQLCipher JNI, Room DAOs, `AquaLegalPortalAuthBridge` `@JavascriptInterface`, WorkManager workers, Android Services, and Wallet BouncyCastle/BIP-39 cryptography, plus strips verbose `Log.d`/`Log.v`/`Log.i` calls.
3. For Google Play production uploads, flip `isMinifyEnabled = true` and `isShrinkResources = true` inside `buildTypes.release` and build with `gradle :app:bundleRelease`.
