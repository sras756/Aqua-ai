# Aqua AI — Android Permissions & Google Play Policy Compliance Guide

---

## 1. AndroidManifest Permission Audit (`app/src/main/AndroidManifest.xml`)

Aqua AI enforces strict **least-privilege** manifest declarations:

### Normal (Install-Time) Permissions
- `android.permission.INTERNET` — Gemini AI Logic, Drive sync, Browser, RPC nodes, and GitHub REST APIs (`android:usesCleartextTraffic="false"`).
- `android.permission.ACCESS_NETWORK_STATE` — Offline awareness & WorkManager `CONNECTED` sync constraint.
- `android.permission.FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` + `FOREGROUND_SERVICE_DATA_SYNC` — Android 14+ typed foreground service (`AquaWakeWordService`) with `onTimeout` handling.
- `android.permission.WAKE_LOCK` & `android.permission.RECEIVE_BOOT_COMPLETED` — Reliable wake-word continuation & scheduled alarm restore (`AquaBootReceiver`).
- `com.android.alarm.permission.SET_ALARM` — Standard Android Clock Alarm intent dispatch.
- `android.permission.USE_BIOMETRIC` — Hardware `BiometricPrompt` gating on wallet seed reveal and Tier 3 signing.

### Runtime Dangerous Permissions (Explicit UI Request Flow Required)
- `android.permission.RECORD_AUDIO` — Voice commands, Gemini Live sessions, and wake-word recognition.
- `android.permission.CAMERA` — QR Code scanning for EIP-681 / BIP-21 / Solana Pay URIs.
- `android.permission.POST_NOTIFICATIONS` — Required on Android 13+ for pending approvals, sync alerts, and task progress (`AquaNotificationChannels`).
- `android.permission.ACCESS_COARSE_LOCATION` — Optional coarse city weather/timezone context when explicitly authorized.

### Special System Bindings (Opt-In User Settings + Prominent Disclosure)
- `AquaAccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`, `@xml/aqua_accessibility_service`) — Gated behind explicit **Prominent Disclosure** acceptance (`AquaAccessibilityService.hasAcceptedDisclosure`) and `PermissionDomain.DEVICE_CONTROL` toggle. Password fields (`isPassword`) are never captured (`AquaScreenIntelligence`).
- `AquaNotificationListenerService` (`BIND_NOTIFICATION_LISTENER_SERVICE`) — Explicitly enabled in Android Notification Access Settings and gated by `PermissionDomain.DEVICE_CONTROL`.

---

## 2. Prohibited Permissions Guarantee (Verified Clean)

The manifest and codebase explicitly **omit** all high-risk/restricted permissions flagged by Google Play Policy (`PlayComplianceInspector`):
- ❌ **No Broad Storage Permissions:** No `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, or `MANAGE_EXTERNAL_STORAGE`. File and media access uses zero-permission **Storage Access Framework (`AquaSafFileController`)** and **Android Photo Picker**.
- ❌ **No Background Location / SMS / Call Log / Phone State:** Zero `ACCESS_BACKGROUND_LOCATION`, `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `CALL_PHONE`. Telephony/messaging actions open standard pre-filled system Intents (`ACTION_DIAL`, `ACTION_SENDTO`) requiring user tap to send.
- ❌ **No Implicit Contacts/Calendar Permissions:** Optional Gmail, Calendar, Contacts, and Photos access uses isolated **Google OAuth 2.0** scopes inside `GoogleConnectorManager`—never `READ_CONTACTS` or `READ_CALENDAR` OS permissions.
- ❌ **No System Alert Window / Query All Packages:** Overlay and package enumeration use Play-compliant `<queries>` declarations (`MAIN/LAUNCHER`, `wc:`, `ethereum:`, `bitcoin:`, `solana:`, `https:`).
- ❌ **No Dynamic Code Loading (DCL):** `SkillParserAndValidator` strictly validates declarative JSON/YAML/Markdown skills and rejects executable `.dex`, `.jar`, or `.so` payloads.

---

## 3. Centralized Permission Policy Store (6 Domains)

Even when OS permissions are granted, `AgentOrchestrator` and `ToolCallingEngine` enforce `PermissionPolicyStore`:
1. `READ_ACCESS`
2. `WRITE_ACCESS`
3. `DEVICE_CONTROL`
4. `CLOUD_STORAGE`
5. `BACKGROUND_EXECUTION`
6. `FINANCIAL_AUTHORIZATION`

Each domain can be toggled independently inside **Ecosystem Hub → Security Center**, with every allow/deny event immutably logged (and secret-redacted via `SecurityAuditRedactor`) in `AuditLogDao`.
