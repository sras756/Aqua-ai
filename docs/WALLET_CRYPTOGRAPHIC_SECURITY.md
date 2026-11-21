# Aqua AI — Wallet Cryptographic & Security Architecture

---

## 1. Non-Custodial Cryptographic Design (`feature:wallet`)

Aqua AI implements a **100% client-side, non-custodial** multichain HD wallet across **EVM** (Ethereum, BSC, Polygon, Base, Arbitrum, Sepolia), **Solana** (Mainnet & Devnet), and **Bitcoin** (Native SegWit Bech32 Mainnet & Testnet4):

- **Mnemonic Entropy & Validation (`HdKeyDerivation`):** Uses `kotlin-bip39` for 12-word (128-bit) and 24-word (256-bit) BIP-39 mnemonic generation with strict wordlist & SHA-256 checksum validation.
- **Deterministic Multi-Chain Key Derivation:**
  - **EVM (`secp256k1`):** Standard BIP-44 path `m/44'/60'/0'/0/{index}` deriving uncompressed/EIP-55 checksummed addresses via BouncyCastle.
  - **Bitcoin Native SegWit (`secp256k1`):** BIP-84 path `m/84'/0'/0'/0/{index}` (Mainnet `bc1q...`) and `m/84'/1'/0'/0/{index}` (Testnet4 `tb1q...`) with BIP-143 SegWit v0 signing.
  - **Solana (`Ed25519`):** SLIP-0010 path `m/44'/501'/{index}'/0'` generating Ed25519 keypairs and Base58 addresses.

---

## 2. Storage, Memory & Screen Hardening

1. **Hardware-Backed Encryption (`WalletSecurityVault`):**
   - Master mnemonics and vault secrets are encrypted using **AES-256-GCM** with random 96-bit IVs wrapped by keys stored in **Android Keystore (`AndroidKeyStore`)**.
   - Stored exclusively in `aqua_native_wallet_vault.xml`.
2. **Android Cloud Backup & Drive Sync Exclusion:**
   - **OS Backup Exclusion:** `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` explicitly exclude `aqua_native_wallet_vault.xml`, `aqua_secure_config_prefs.xml`, `aqua_db_key_prefs.xml`, `aqua_security_policy_prefs.xml`, and `aqua_ssot_encrypted.db*` from Android cloud backup and device-to-device transfer.
   - **Drive Sync Firewall (`SecretExclusionFilter`):** Every mutation entering `AquaDriveSyncCoordinator` is inspected before enqueueing and again before HTTP upload. File names or JSON payloads containing mnemonics, private keys, PINs, vault XML references, Bearer/OAuth tokens, or GitHub PATs are hard-rejected.
3. **Screenshot & Recents Protection (`WalletScreenSecurityHelper`):**
   - `SecureWalletWindowEffect` (`DisposableEffect`) applies `WindowManager.LayoutParams.FLAG_SECURE` whenever `WalletScreen` enters composition and clears it cleanly upon leaving, blocking screenshots and unencrypted OS recents thumbnails of sensitive wallet views.
4. **Spending Limits & Emergency Lock:**
   - Enforces configurable `perTxLimitUsd`, `dailyLimitUsd`, `hasPin`, `biometricEnabled`, and `emergencyLocked`.

---

## 3. Strict AI Air-Gap (`AquaAiWalletBridge`)

Gemini and `ToolCallingEngine` **never** have programmatic access to `WalletSecurityVault` secret extraction APIs:

| Operation | Permitted to AI (`AquaAiWalletBridge`)? | Security Enforcement |
|---|---|---|
| View public addresses, balances, fees, ENS, allowances | ✅ Read-only (when `aiWalletAccessEnabled = true`) | Never exposes mnemonics, raw private keys, or PINs |
| Analyze recipient risk & scam heuristics | ✅ Always | Runs `AquaSecurityGuardian` + chain risk checks |
| Prepare unsigned transaction (`PreparedChainTransaction`) | ✅ When `aiWalletTransferPrepareEnabled = true` | Returns unsigned parameters & fee simulation only |
| Sign / Broadcast transaction | ❌ Never autonomous | Gated by `RiskTier.TIER_3_BIOMETRIC_CONFIRM`, UI confirmation card, and `BiometricPrompt` / PIN verification |
| Reveal 12/24-word recovery phrase | ❌ Strictly forbidden to AI | Native UI only behind explicit user biometric/PIN unlock |
| Unlock Emergency Lock / Raise Spending Limits | ❌ Strictly forbidden to AI | Native Wallet Security settings only |

---

## 4. Transaction & Link Threat Protection (`AquaSecurityGuardian`)

Prior to signing or transaction preparation (`AquaWalletEngine.prepareTransaction` + UI inspection):
- **Burn / Dead Address Detection:** Flags known dead/burn addresses (`0x000...000`, `0x000...dead`).
- **Unlimited ERC-20 Approval Trap (`0x095ea7b3`):** Detects `MAX_UINT256` / unlimited allowance approvals in calldata and warns the user explicitly.
- **Phishing & Homograph URL Detection:** Inspects URLs across Browser and QR Scanner for IDN homographs (`xn--...`), lookalike wallet/airdrop claim keywords, unsafe schemes (`javascript:`, `file:`, `data:`), and risky `.apk` downloads.
- **Duplicate Submission Guard:** Enforces deterministic `idempotencyKey` tracking to prevent accidental double-broadcasts.
