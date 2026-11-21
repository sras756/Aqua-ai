# Aqua AI — Final System Architecture Specification

**Application ID:** `com.aistudio.aquaai.vqrzmn`  
**Namespace:** `com.example`  
**Minimum / Target / Compile SDK:** `minSdk = 24` / `targetSdk = 36` / `compileSdk = 36.1`  
**Build Toolchain:** AGP `9.1.1`, Kotlin `2.2.10`, KSP `2.3.6`, Jetpack Compose Material 3 (`2024.09.00` BOM)

---

## 1. Architectural Principles

Aqua AI is structured as a modular, local-first, voice-first autonomous Android assistant grounded in five core invariants:

1. **Encrypted Single Source of Truth (SSOT):** All persistent conversations, messages, FTS4 memory records, dual-mode workspace documents, browser bookmarks, agent tasks, immutable redacted audit logs, offline Drive mutation queues, AI token metering, and automation workflows reside in `AquaCacheDatabase` (Room v4, `aqua_ssot_encrypted.db`), encrypted via SQLCipher (`net.zetetic:sqlcipher-android:4.6.1`) with a 256-bit Data Encryption Key managed by `AquaDatabaseKeyManager` inside Android Keystore.
2. **Supervised Autonomous Agency:** No tool can bypass `PermissionPolicyStore` (6 independent `PermissionDomain` toggles) or `ToolRiskClassifier` (Tier 0–3 escalation). Every state transition, approval, denial, or cancellation is recorded immutably in `AuditLogDao` via `SecurityAuditRedactor`.
3. **Strict Crypto Air-Gap:** Gemini (`GeminiProvider`) and `ToolCallingEngine` interact with Web3 exclusively through `AquaAiWalletBridge`—reading public addresses/balances and preparing unsigned transactions (`PreparedChainTransaction`). BIP-39 phrases and private keys never cross out of `WalletSecurityVault` into LLM prompts, Drive backups, or Room databases.
4. **Prompt-Injection & Origin Isolation:** External DOM text extracted from `AquaBrowserController` / `BrowserScreen` is sanitized and wrapped inside `[UNTRUSTED_WEBPAGE_DATA_START] ... [UNTRUSTED_WEBPAGE_DATA_END]` by `PromptInjectionFirewall`, neutralizing instruction-override, wallet-signing coercion, and seed-exfiltration payloads.
5. **Play-Policy Least Privilege:** Zero broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `MANAGE_EXTERNAL_STORAGE`), zero implicit PII permissions (`READ_CONTACTS`, `READ_CALENDAR`), explicit opt-in disclosure for `AquaAccessibilityService`, and SAF / Photo Picker (`ActivityResultContracts.OpenDocument`, `OpenDocumentTree`, `PickVisualMedia`) for user-authorized file access.

---

## 2. Package & Module Topology

| Layer / Module | Package Boundary | Key Production Classes |
|---|---|---|
| **Common & Crypto Hashing** | `com.example.core.common` | `AquaDispatchers`, `CryptoHashing` |
| **Centralized Security** | `com.example.core.security` | `PermissionDomain` (6 domains), `PermissionPolicyStore`, `SecurityAuditRedactor`, `SecretExclusionFilter`, `AquaDatabaseKeyManager`, `AquaSecurityGuardian`, `PlayComplianceInspector`, `GoogleSignatureVerifier` |
| **Encrypted Room SSOT** | `com.example.core.database` & `com.example.data.local` | `AquaCacheDatabase` (v4 SQLCipher), `ConversationEntity`, `ChatMessageEntity`, `MemoryEntity` + `MemoryFtsEntity`, `WorkspaceDocumentEntity` + `WorkspaceDocumentFtsEntity`, `BookmarkEntity`, `AgentTaskEntity`, `AuditLogEntity`, `DriveSyncQueueEntity`, `DriveFileMetadataEntity`, `AiUsageMeterEntity`, `AutomationWorkflowEntity`, `SecureConfigStore`, `AquaLocalPreferences` |
| **Gemini Intelligence (`core:ai`)** | `com.example.core.ai` & `com.example.ai` | `GeminiProvider`, `ContextManager`, `ContextCompactor`, `AiUsageMeterTracker`, `ExponentialBackoffExecutor`, `TaskPlanner`, `ConversationMarkdownExporter`, `SkillParserAndValidator` |
| **Bilingual Voice (`core:voice`)** | `com.example.core.voice` & `com.example.voice` | `VoiceAssistantManager`, `AquaAudioFocusController`, `AquaVoiceLanguageDetector` (English, Urdu script, Roman Urdu), `SpeechErrorMapper`, `AquaWakeWordService` (`microphone|dataSync` FGS), `AquaBootReceiver` |
| **Supervised Agent (`core:agent`)** | `com.example.core.agent` & `com.example.ai` | `AgentOrchestrator`, `TaskState` (`CREATED -> PLANNING -> AWAITING_APPROVAL -> EXECUTING -> VERIFYING -> COMPLETED/FAILED/CANCELLED/ROLLED_BACK`), `ToolRiskClassifier` (59 tools, Tiers 0–3), `ToolCallingEngine` |
| **Device Control (`core:device`)** | `com.example.core.device` & `com.example.device` | `AndroidDeviceController`, `AquaAccessibilityService`, `AquaNotificationListenerService`, `AquaScreenIntelligence`, `AquaSafFileController`, `MediaPlaybackController` |
| **Drive Sync & Continuity (`core:drive`)** | `com.example.core.drive` & `com.example.data.repository` | `AquaDriveFolders` (10 folders under `Aqua AI/`), `AquaDriveSyncCoordinator`, `AquaDriveSyncWorker` (WorkManager), `GoogleDriveHistoryRepository`, `ConflictResolutionStrategy` (`KEEP_LOCAL`, `KEEP_CLOUD`, `SAVE_BOTH_COPY`) |
| **Memory Engine (`core:memory`)** | `com.example.core.memory` | `AquaMemoryEngine` (`SHORT_TERM`, `LONG_TERM`, `EPISODIC`, FTS4 search, confidence/provenance, JSON/Markdown export, permanent delete propagation) |
| **Browser & Dual Workspace** | `com.example.browser`, `com.example.core.browser`, `com.example.core.workspace` | `AquaBrowserController` (multi-tab + Incognito isolation), `PromptInjectionFirewall`, `AquaWebClient` (SSRF private IP block), `AquaLegalPortalAuthBridge`, `AquaWorkspaceEngine` (`USER_VIEW` read-only vs `AI_WORKSPACE` read-write, FTS4, annotations) |
| **Multichain Wallet (`feature:wallet`)** | `com.example.wallet.*` | `WalletSecurityVault` (Android Keystore AES-256-GCM + biometric/PIN guard), `HdKeyDerivation` (BIP-39 / BIP-32 / secp256k1 / Ed25519), `EvmChainAdapter`, `SolanaChainAdapter`, `BitcoinChainAdapter`, `AquaWalletEngine`, `AquaWalletCoordinator`, `AquaAiWalletBridge`, `WalletScreenSecurityHelper` (`FLAG_SECURE`) |
| **Automation, Brain & Connectors** | `com.example.core.{automation,knowledge,connectors,developer,notifications}` | `AquaAutomationStudio` + `AquaAutomationWorker`, `AquaKnowledgeBrain` (RAG over Memory + Workspace Docs + Drive Metadata with verifiable citations), `GoogleConnectorManager` (Gmail, Calendar, Contacts, Photos isolated OAuth), `AquaDeveloperModeClient` (GitHub REST v3), `AquaNotificationChannels` (4 priority channels) |
| **UI & Navigation Layer** | `com.example.ui.*` | `MainActivity` (App Check, Edge-to-Edge, Notification channels), `AquaRootApp`, `AquaNavigation` (`AquaAdaptiveContainer` Phone `NavigationBar` + Tablet/Foldable `NavigationRail`), `AquaMainViewModel`, `HomeScreen`, `ChatScreen`, `VoiceScreen`, `BrowserScreen`, `WalletScreen`, `MemoryAndHistoryScreen`, `DriveVaultScreen`, `AquaEcosystemHubScreen`, `HubScreen`, `SettingsScreen` |

---

## 3. Data Flow & Concurrency Model

- **Dispatcher Injection:** All Room, file, Drive HTTP, and cryptographic operations run on `Dispatchers.IO` or `AquaDispatchers` cleanly off the Main thread.
- **State Exposure:** `AquaMainViewModel` collects Room `Flow` streams (`conversations`, `memories`, `workspaceDocs`, `bookmarks`, `pendingSyncCount`, `syncConflicts`, `auditLogs`, `workflows`) into `StateFlow` collected via `collectAsStateWithLifecycle()`.
- **Task Cancellation:** `AgentOrchestrator.registerJob(taskId, job)` binds active coroutine `Job` instances so user pause/stop clicks immediately cancel execution, invoke rollback hooks when defined, and transition the SSOT row to `TaskState.CANCELLED`.
- **Adaptive Tablet/Foldable UI:** `AquaAdaptiveSpec` dynamically adapts grids, dual-pane views, and bottom bar vs `NavigationRail` across Compact, Medium, and Expanded Window Size Classes.
