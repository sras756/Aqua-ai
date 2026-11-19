# Aqua AI

**Aqua AI** (`com.aistudio.aquaai.vqrzmn`) is a native Android personal AI assistant, Gemini Live voice companion, Google Drive Vault backup client, Android SDK & Google Signature verifier, and non-custodial multi-chain crypto wallet built with Kotlin and Jetpack Compose.

---

## Clean Modular Architecture & UI

Aqua AI follows a clean **MVVM + Coordinator** architecture with adaptive Material Design 3 surfaces:

- **Dedicated State Coordinators**:
  - `AquaSystemAndDriveCoordinator` — Manages Google Drive Vault OAuth, storage quota checks, `GOOGLE_DRIVE_API_KEY` key verification/storage, and live Android SDK + APK Signature telemetry.
  - `AquaWalletCoordinator` — Isolates BIP-39 key derivation, Android Keystore AES-256-GCM encryption, multi-chain RPC balance polling, and transaction signing boundaries.
- **Design System & Motion**:
  - Custom tokens in `AquaAtmosphere.kt` (`AquaBackdropBox`, `AquaGlassCard`, `AquaSectionHeader`, `AquaStatusPill`) and `AquaMotion.kt` (`AquaAnimatedEntrance`, spring-physics press feedback).
  - Adaptive navigation switching seamlessly between a **5-tab NavigationBar** (`Core`, `Routines`, `Browser`, `Wallet`, `Hub`) on phones and a **NavigationRail** on tablets/foldables.
  - Sub-routes inside Hub: `Drive Vault` (`"drive"`), `SDK & Signature` (`"sdk"`), `Skills Engine`, `Memory & Logs`, and `Settings & Age Profile`.

---

## Key Capabilities

- **Google Signature Check (`GoogleSignatureVerifier`)**:
  - Inspects live APK signing certificates (`SHA-1` & `SHA-256` via `PackageManager.GET_SIGNING_CERTIFICATES` / legacy `GET_SIGNATURES`).
  - Compares against registered Firebase OAuth `SHA-1` (`5B:FA:1D:7E:BF:7D:72:49:B5:AF:83:9C:1B:E6:98:04:65:45:B9:07`), verifies `default_web_client_id`, checks `GoogleApiAvailability` Play Services health, and confirms `google.com` Firebase linkage.
  - Surfaced directly on `AuthScreen`, `SdkAndSignatureScreen`, `SettingsScreen`, and the `inspect_android_sdk_and_signature` AI tool.
- **Google Drive Vault + Approved API Key (`DriveVaultScreen`)**:
  - Google Workspace (Gmail, Calendar, Contacts, Sheets, Forms, Chat) has been completely removed for least-privilege scope hygiene.
  - Dedicated Drive API v3 client using Credential Manager `AuthorizationClient` (`drive.appdata` + `drive.file`) and optional approved **`GOOGLE_DRIVE_API_KEY`** project attribution (via AI Studio Secrets / `BuildConfig` or per-device Keystore AES-256-GCM override).
  - Features Drive `/about` storage quota progress bar, one-tap AppData/My Drive JSON snapshot backup, restore into Firestore, and approval-gated backup deletion.
  - AI tools: `backup_history_to_drive`, `drive_list_backups`, and `drive_get_storage_quota`.
- **Android SDK Inspector (`AndroidSdkInspector` & `SdkAndSignatureScreen`)**:
  - Displays live `Build.VERSION.SDK_INT`, `RELEASE`, `CODENAME`, `SECURITY_PATCH`, Compile/Target/Min SDK alignment, supported ABIs, CPU cores, JVM heap, and a real `PackageManager.hasSystemFeature(...)` hardware matrix (StrongBox Keystore, Biometrics, Camera, Mic, BLE, NFC, Wi-Fi), with one-tap Markdown copy/export.
- **Gemini AI, BYOK Setup Studio, Age 13+ & ElevenLabs Voice**:
  - Streaming chat, Deep Thinking traces, Gemini Live duplex voice, AI Personas, Prompt Studio, and AI Dependency & Freedom Lab sliders.
  - Optional personal **Google AI Studio API Key** and optional (non-compulsory) **ElevenLabs API Key & Voice ID**.
  - COPPA-compliant **Age 13+ Gate** (blocks <13, enforces Teen 13–17 Balanced guardrails) and optional Gender/pronoun preference.
- **Priority Foreground Service**:
  - Dual-type (`microphone | dataSync`) Android 14–16 compliant foreground service (`AquaWakeWordService`) with persistent notification and Pause/Resume/Stop controls.
- **Aqua Browser & Live Web**:
  - Integrated Chromium WebView browser with reader extraction and SSRF-safe DuckDuckGo/Wikipedia search + HTTP tools.
- **Non-Custodial Multi-Chain Wallet**:
  - Device-only Keystore AES-256-GCM BIP-39/BIP-44/BIP-84/SLIP-10 engine supporting Ethereum/EVM L2s, Solana, and Bitcoin SegWit, with ENS lookup, ERC-20 revokes, QR scanner, PIN/Biometric lock, and CoinGecko charts.

---

## Android SDK Setup & Build Instructions

### SDK & Toolchain Requirements
| Component | Version / Value |
|---|---|
| **Compile SDK** | Android 16 — `API 36` (`minorApiLevel = 1` / `36.1`) |
| **Target SDK** | `36` (Android 16) |
| **Minimum SDK** | `24` (Android 7.0 Nougat) |
| **JDK** | `17` or higher (`sourceCompatibility` / `targetCompatibility = 11`) |
| **Android Gradle Plugin** | `9.1.1` (Kotlin `2.2.10`, KSP `2.3.6`) |
| **Gradle** | `9.3.1` |

### Local Android SDK Installation
Ensure your Android SDK includes platform API 36 and build tools:
```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

### API Keys & Secrets Configuration
Declare optional keys in the **AI Studio Secrets panel** (or local `.env`, never commit real keys):
- `GOOGLE_DRIVE_API_KEY=` — Approved Google Cloud Google Drive API Key injected as `BuildConfig.GOOGLE_DRIVE_API_KEY` (can also be overridden per user on-device via Keystore AES-256-GCM inside **Drive Vault**).

### Build APK, AAB & Run Unit Tests
> Always invoke `gradle` directly (`./gradlew` is not used in this container).

```bash
# Run Robolectric & unit test suite
gradle :app:testDebugUnitTest

# Build installable APK & Android App Bundle (AAB)
gradle :app:assembleDebug :app:bundleDebug

# Outputs:
# - app/build/outputs/apk/debug/app-debug.apk
# - app/build/outputs/bundle/debug/app-debug.aab
```

### Automated GitHub Packages & GitHub Pages Legal Portal
Once pushed to a **Public** GitHub repository:
1. Open the **Actions** tab to run **Build & Publish Aqua AI Android Package** (`.github/workflows/android-release.yml`) to build debug/release APKs & AABs (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`).
2. Publish the interactive legal portal (`index.html`, `privacy.html`, `terms.html`) via **Settings → Pages → Source: GitHub Actions** (`https://sras756.github.io/Aqua-ai/`). Both Privacy Policy and Terms & Conditions include live **Sign in with Google** (Google Identity Services) client-side verification and local policy acknowledgement badges.
3. **External Web Browser OAuth Origin Setup (Fixes `Error 400: origin_mismatch`)**:
   - In [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials?project=gen-lang-client-0405170912), open OAuth 2.0 Web Client `299712147014-tr9jkmpjf6nj5h66ekissl1arb2es3rp.apps.googleusercontent.com`.
   - Under **Authorized JavaScript origins**, add the exact origin (scheme + host only, no trailing slash or `/Aqua-ai/` path):
     - `https://sras756.github.io`
   - Inside the Aqua AI Android app (Legal Portal modal and Aqua Browser), Google Sign-In automatically uses native Android Credential Manager (`AquaLegalPortalAuthBridge`) and works immediately.

#### GitHub Release Assets
Public releases include the newest signed `Aqua-AI.apk`, its `.sha256` checksum, the signed `Aqua-AI.aab`, and its `.sha256` checksum. Release builds verify the Google Play App Signing certificate SHA-256:
`D4:66:B3:05:C7:B8:97:FF:3B:0A:5C:BA:E0:DE:AC:A7:74:FD:70:C4:3E:4F:B3:A9:8D:6B:97:E2:75:CD:65:A2`.

---

## Legal Portal & Support
- [Legal & Capabilities Portal](https://sras756.github.io/Aqua-ai/) (`index.html`)
- [Privacy Policy](https://sras756.github.io/Aqua-ai/privacy.html) (`privacy.html`)
- [Terms & Conditions](https://sras756.github.io/Aqua-ai/terms.html) (`terms.html`)
- Developer Support: `srasmarkhor7289@gmail.com`
