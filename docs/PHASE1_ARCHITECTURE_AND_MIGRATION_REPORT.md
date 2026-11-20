# Aqua AI — Phase 1 Architecture & Migration Report

**Phase:** 1 — Existing Project Inspection, Dependency Audit, Modular Core & Encrypted Room Baseline  
**Application ID:** `com.aistudio.aquaai.vqrzmn`  
**Namespace:** `com.example`  
**SDK Targets:** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36.1`  

---

## 1. Codebase Inventory

### 1.1 Build System, Toolchain & Configuration
- **Gradle & Plugins:** AGP `9.1.1`, Kotlin `2.2.10`, KSP `2.3.6`, Jetpack Compose BOM `2024.09.00`, Google Services `4.5.0`, Secrets Gradle Plugin `2.0.1` (`gradle/libs.versions.toml`, `app/build.gradle.kts`).
- **Signing & ProGuard/R8:** Debug & release `signingConfigs` preserved untouched in `app/build.gradle.kts`; `proguard-rules.pro` currently minimal (targeted for R8 hardening in Phase 10).
- **Backup Rules:** `AndroidManifest.xml` specifies `android:allowBackup="true"` with `backup_rules.xml` and `data_extraction_rules.xml`. Phase 1 hardens both XML rule files to explicitly exclude wallet vault preferences, Keystore-encrypted API configuration, DB encryption key preferences, and encrypted Room databases from Android Auto Backup and D2D transfers.

### 1.2 Entry Point, UI Architecture & Navigation
- **Entry Point:** `MainActivity` (`FragmentActivity`, required by `BiometricPrompt`) enforces `hasOfficialPlaySigningCertificate(this)`, initializes Firebase App Check via `configureAppCheck(this, intent)`, enables edge-to-edge (`enableEdgeToEdge()`), handles `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, `EXTRA_WAKE_COMMAND`, and `aqua_browser_query` deep links, and mounts `AquaRootApp`.
- **DI & State:** Manual dependency construction via `viewModelFactory` inside `AquaNavigation.kt` (`AuthenticatedAquaShell`), scoped to authenticated `user.uid` (`key(user.uid)`). `AquaMainViewModel` exposes `StateFlow` streams collected via `collectAsStateWithLifecycle()`.
- **Design System & Adaptive UI:** `AquaAiTheme` (`Theme.kt`, `Color.kt`, `Type.kt` with bundled Google Fonts) + `AquaAdaptiveContainer` switching between compact bottom `NavigationBar` and tablet/foldable `NavigationRail`.

### 1.3 Authentication, Persistence & AI Clients
- **Authentication:** Google Sign-In via Jetpack `CredentialManager` (`GetSignInWithGoogleOption` / `GetGoogleIdOption`) in `AuthManager.kt` & `AuthScreen.kt`, backed by Firebase Auth.
- **Cloud Database:** `AquaFirestoreRepository` targeting named Firestore instance `R.string.firestore_database_id` with zero-trust rules in `firestore.rules` + `firestore.test.js`.
- **Local Database (Pre-Phase 1):** `AquaCacheDatabase` (Room v3, unencrypted `aqua_local_cache.db`) storing only device action logs and wallet cache entities (`CachedWalletTxEntity`, `WalletCustomTokenEntity`, `WalletCustomNetworkEntity`, `WalletAddressBookEntity`).
- **AI & Voice:** `GeminiProvider` supports Firebase AI Logic (`Firebase.ai`, `gemini-3.8-flash` / `gemini-3.5-flash-lite` / `gemini-4-argon` / live session) plus optional user-supplied Google AI Studio REST mode via `SecureConfigStore`. `VoiceAssistantManager` integrates `SpeechRecognizer`, `TextToSpeech`, ElevenLabs REST streaming, and Gemini TTS PCM playback. `AquaWakeWordService` runs a Play-compliant `microphone|dataSync` foreground service with Android 15+ `onTimeout` handling.

### 1.4 Existing Security, Browser, Wallet & Device Components
- **Browser:** `AquaBrowserController` + `BrowserScreen` + `AquaLegalPortalAuthBridge` + `AquaWebClient` (SSRF private-IP blocking).
- **Wallet:** Complete non-custodial multi-chain engine (`WalletSecurityVault`, `HdKeyDerivation` via `kotlin-bip39` + `BouncyCastle`, `EvmChainAdapter`, `SolanaChainAdapter`, `BitcoinChainAdapter`, `AquaWalletEngine`, `AquaAiWalletBridge`, `BiometricAuthHelper`, ML Kit QR scanner).
- **Device Control:** `AndroidDeviceController`, `AquaNotificationListenerService`, `AquaBootReceiver`.
- **Manifest Permissions:** Least-privilege set (`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `USE_BIOMETRIC`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_COARSE_LOCATION`, `SET_ALARM`), plus `<queries>` for launcher apps & `wc:`, `ethereum:`, `bitcoin:`, `solana:`, `https:`. Zero restricted broad-storage permissions.

---

## 2. Dependency & Gap Map

### 2.1 Preserved As-Is (Working Foundation)
- Firebase Auth + Credential Manager sign-in and `AuthScreen`.
- `AquaTheme`, custom font typography, `AquaAtmosphere`, `AquaAdaptiveContainer` (phone + tablet/foldable support), and animated Orb UI.
- `WalletSecurityVault` AES-256-GCM hardware Keystore encryption, BIP-39/BIP-32/secp256k1/Ed25519 derivation, `AquaAiWalletBridge` air-gap.
- `PlayComplianceInspector`, `GoogleSignatureVerifier`, `PlaySigningCertificate`, `AquaWebClient` SSRF firewall, `SkillParserAndValidator`.
- `AquaWakeWordService` foreground service lifecycle.

### 2.2 Components Wrapped, Extended, or Migrated
| Component | Current State | Target Architecture |
|---|---|---|
| `AquaCacheDatabase` (v3 plaintext cache) | Unencrypted cache for logs & wallet metadata | Encrypted SQLCipher Room v4 SSOT (`aqua_ssot_encrypted.db`) storing conversations, messages, FTS4 memory, workspace docs, bookmarks, agent tasks, immutable audit logs, Drive sync queue, AI usage metrics, and automation workflows |
| `SecureConfigStore` / `AquaLocalPreferences` | Encrypted config keys & plain companion prefs | Retain working APIs; add `AquaDatabaseKeyManager`, `PermissionPolicyStore` (6 domains), `SecurityAuditRedactor`, and `SecretExclusionFilter` |
| `GeminiProvider` & `VoiceAssistantManager` | Functional Firebase AI / live / STT / TTS | Add token/usage metering, exponential backoff retry, context compaction, English + Urdu voice/auto-detect, and `AudioManager` focus negotiation |
| `ToolCallingEngine` | Inline confirmation deferred dialogs | Formal `AgentTask` state machine (`CREATED` -> `COMPLETED`/`FAILED`/`CANCELLED`/`ROLLED_BACK`), Tier 0–3 Risk Matrix, pause/cancel `Job` control, immutable `AuditLogEntity` |
| `GoogleDriveHistoryRepository` | Single JSON AppData / root export | 10-folder `Aqua AI/` hierarchy, `OfflineSyncQueue` + `WorkManager`, ETag/SHA-256/modifiedTime conflict resolution, pre-upload `SecretExclusionFilter` |
| `AquaBrowserController` & `BrowserScreen` | Single-session WebView + Reader | Multi-tab + Private/Incognito isolation, strict `allowFileAccess=false`, Bookmarks Drive sync, Prompt-Injection Untrusted-Data Firewall, Dual **User View / AI Workspace** |

### 2.3 Missing Modules & Libraries Added
- **Phase 1 Dependencies Added:**
  - `net.zetetic:sqlcipher-android:4.6.1` + `androidx.sqlite:sqlite:2.5.0` (SQLCipher AES-256 Room encryption)
  - `androidx.work:work-runtime-ktx:2.10.1` + `androidx.work:work-testing:2.10.1` (reliable offline Drive sync & scheduled automation)
- **Target Feature Modules / Packages Missing Pre-Phase 1:**
  - `AquaKnowledgeBrain` (local + Drive FTS/RAG grounded Q&A with citations)
  - `AquaAutomationStudio` (WorkManager scheduled & conditional workflows)
  - `AquaAccessibilityService` & `MediaSessionManager` bridge (with prominent Play-compliant disclosure)
  - `AquaFloatingAssistant`, `AquaScreenIntelligence`, `AquaSecurityGuardian`, `AquaFileManager`, `AquaDeveloperMode` (GitHub integration), `AquaNotifications`, modular `GoogleConnector` registry (Gmail, Calendar, Contacts, Photos).

### 2.4 Google Cloud & OAuth Prerequisites
- `google-services.json` and `firebase_applet_config.xml` already provision Firebase project, Firestore database ID, Web Client ID (`default_web_client_id`), and App Check.
- Google Drive OAuth 2.0 (`drive.appdata`, `drive.file`) and optional modular Google Connectors require APK debug/release SHA-1 registered in Google Cloud OAuth Android Client.

---

## 3. Incremental Modular Package Boundaries

To preserve full build stability and Android `R` / Firestore / Wallet compatibility while enforcing clean decoupled architecture:

- `com.example.core.common`: Coroutine dispatchers (`AquaDispatchers`), clock abstraction, SHA-256 content hashing.
- `com.example.core.security`: 6 Granular Permission Domains (`PermissionDomain`, `PermissionPolicyStore`), `SecurityAuditRedactor`, `SecretExclusionFilter`, `AquaDatabaseKeyManager`.
- `com.example.core.database`: Encrypted SQLCipher Room SSOT (`AquaCacheDatabase` v4, SSOT entities, FTS4 index, DAOs, database factory).
- `com.example.core.network`: Hardened HTTP & SSRF-protected transport (`AquaWebClient` wrappers).
- `com.example.core.ai`: Model routing, usage metering, context compaction, retry/backoff, bilingual EN/UR structured inference (`GeminiProvider`).
- `com.example.core.voice`: Audio focus manager, EN/UR multilingual STT/TTS & barge-in (`VoiceAssistantManager`, `AquaWakeWordService`).
- `com.example.core.device`: Permission-gated Intents, Accessibility, MediaSession, SAF, NotificationListener (`AndroidDeviceController`).
- `com.example.core.drive`: 10-folder `Aqua AI/` Drive manager, WorkManager sync worker, conflict resolver (`GoogleDriveHistoryRepository`).
- `com.example.core.memory`: FTS4 persistent memory tiers, provenance, export/delete propagation.
- `com.example.core.agent`: Supervised Autonomous Agent Engine, Task State Machine, Tier 0–3 Risk Matrix, immutable `AuditLogEntity`.
- `com.example.feature.browser`: Multi-tab / Incognito WebView, Reader, Prompt-Injection Firewall, User View / AI Workspace.
- `com.example.feature.wallet`: Non-custodial adapters (`EvmChainAdapter`, `SolanaChainAdapter`, `BitcoinChainAdapter`), `FLAG_SECURE`, Biometric signing, Security Guardian integration.
- `com.example.feature.automation` & `com.example.feature.connectors`: Automation Studio, Knowledge Brain, Security Guardian, File Manager, Developer Mode, Modular `GoogleConnector` plugins.
