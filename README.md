# Aqua AI

**Aqua AI** (`com.aistudio.aquaai.vqrzmn`) is a native Android personal AI assistant, standalone & integrated Android home-screen launcher (**Aqua Launcher**), live voice companion, and non-custodial multi-chain wallet built with Kotlin and Jetpack Compose.

## Key Capabilities

- **Gemini AI & Live Voice**: Streaming conversations, Gemini Live bidirectional voice sessions, continuous wake-word listening, and tool calling via Firebase AI Logic with optional Direct API fallback.
- **Priority Foreground Service**: Dual-type (`microphone | dataSync`) Android 14/15/16 compliant foreground service with persistent notification keeping voice conversations and automation tasks active in the background.
- **Aqua Launcher (Standalone & In-App)**: Real `HOME` replacement launcher and integrated dashboard featuring a customizable workspace, smart app drawer, universal search, native widget hosting, gestures, and live map/aurora wallpaper engine.
- **Autonomous Routines & Skills**: Author multi-step routines, inspect hardware pulse, and import validated declarative JSON/YAML/Markdown skills.
- **Aqua Browser & Live Web**: Integrated WebView browser with reader extraction, summarization, and public web search/HTTP tools.
- **Google Workspace & Drive (Optional)**: User-authorized integrations for Gmail, Calendar, Contacts, Sheets, Forms, Chat, and Google Drive conversation backups.
- **Non-Custodial Multi-Chain Wallet**: Local Keystore-encrypted BIP39/BIP44/BIP84/SLIP10 wallet supporting Ethereum, EVM L2s, Solana, and Bitcoin with biometric/PIN locking and explicit transfer authorization.

## Building the Android Package (APK & AAB)

### In AI Studio
1. Open the AI Studio **Settings** menu and choose **Generate APK / AAB** to export an installable package directly.
2. Choose **Push to GitHub** in Settings to connect your GitHub account and set the repository visibility to **Public**.

### Locally with Gradle (JDK 17+ & Android SDK)
```bash
# Build installable APK & Android App Bundle (AAB)
gradle :app:assembleDebug :app:bundleDebug

# Outputs:
# - app/build/outputs/apk/debug/app-debug.apk
# - app/build/outputs/bundle/debug/app-debug.aab
```

### Automated GitHub Packages & Releases
Once pushed to a **Public** GitHub repository:
1. Open the **Actions** tab to run **Build & Publish Aqua AI Android Package** (runs automatically on pushes to `main`, `v*` tags, or manual trigger) and download `aqua-ai-android-packages` (APK + AAB) or grab it from **Releases**.
2. To build signed release packages in CI, configure repository secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD`.
3. To publish the Privacy Policy and Terms of Service site (`index.html`, `privacy.html`, `terms.html`), go to **Settings → Pages** and set **Source** to **GitHub Actions**.

## Legal & Support
- [Privacy Policy](privacy.html)
- [Terms & Conditions](terms.html)
- Developer Support: `srasmarkhor7289@gmail.com`
