# Aqua AI — Google Drive Sync & Conflict Resolution Specification

## 1. Architectural Role
- **Offline-First SSOT:** Local encrypted Room + SQLCipher (`aqua_ssot_encrypted.db`, Version 4) is always the authoritative primary store.
- **Cloud Destination:** Google Drive REST API v3 operates asynchronously as the synchronized workspace mirror and backup store via `AquaDriveSyncCoordinator` and `AquaDriveSyncWorker` (`WorkManager`).
- **Least-Privilege OAuth 2.0 Scope:** Uses Android Credential Manager `AuthorizationClient` requesting app-scoped Drive access (`drive.appdata` and `drive.file`) — never full unrestricted Google Drive scopes by default.

## 2. Mandatory 10-Folder `Aqua AI/` Hierarchy
`AquaDriveSyncCoordinator.ensureTenFolderHierarchy` idempotently looks up and creates the root folder `Aqua AI/` and its 10 isolated domain folders:

1. `Account data/` — account preferences and non-secret profile metadata
2. `Conversations/` — serialized conversation snapshots and summaries
3. `Memory/` — Long-term and Episodic `MemoryEntity` records (`UPSERT` and `DELETE` tombstones)
4. `AI documents/` — AI Workspace documents (`WorkspaceFolderType.AI_DOCUMENTS`)
5. `Research/` — Web research reports with verifiable citations (`WorkspaceFolderType.RESEARCH`)
6. `Browser bookmarks/` — User bookmarks saved from non-Incognito browser tabs
7. `Task history/` — Supervised Autonomous Agent execution summaries
8. `Automation records/` — `AutomationWorkflowEntity` definitions synced from Automation Studio
9. `Backups/` — Full structured conversation + memory JSON snapshots
10. `Exports/` — User-initiated Markdown and JSON document exports

Folder IDs are cached per `userId` in SharedPreferences to avoid redundant Drive search queries.

## 3. Offline Change Queue & Background Processing
All local mutations pass through `AquaDriveSyncCoordinator.enqueueMutation`:
1. Computes a canonical SHA-256 digest (`CryptoHashing.sha256Hex`) of `serializedPayloadJson`.
2. Persists a `DriveSyncQueueEntity` row (`status = PENDING_OFFLINE`) in Room.
3. Schedules unique `OneTimeWorkRequest` (`AquaDriveSyncWorker`) constrained on `NetworkType.CONNECTED` with `BackoffPolicy.EXPONENTIAL` (30s initial backoff).
4. Checks `PermissionPolicyStore.isAllowed(PermissionDomain.CLOUD_STORAGE)` before enqueueing/flushing remote HTTP traffic.

## 4. Non-Negotiable Secret Exclusion Filter
Before **any** payload enters `ssot_drive_sync_queue` and again immediately before wire transmission, `SecretExclusionFilter.inspectForDriveUpload` blocks:
- Restricted Vault & Keystore files: `aqua_native_wallet_vault`, `aqua_secure_config_prefs`, `aqua_db_key_prefs`, `aqua_ssot_encrypted.db`, `aqua_local_cache.db`, `wallet_secrets`, `keystore`, `private_key`, `seed_phrase`
- Secret keys & markers: `"mnemonic"`, `"privateKey"`, `"seedPhrase"`, `"wallet_pin_hash"`, `"enc_secret_"`, `"enc_gemini_api_key"`, `"enc_elevenlabs_api_key"`, `"aqua_encrypted_wallet_v1"`, PEM private headers, OAuth bearer tokens (`ya29.`), GitHub PATs (`ghp_`, `gho_`), Google API keys (`AIza`), and labeled raw 256-bit hex private keys.

## 5. Conflict Detection, Preservation & Resolution
When flushing an `UPSERT` for an existing Drive file:
1. Coordinator compares local baseline metadata (`DriveFileMetadataEntity.versionTag` and `localSha256`) against remote Drive metadata (`version` / `md5Checksum`).
2. **Conflict Trigger:** Remote `version` differs from last-known `versionTag` AND `localSha256 != payloadSha256`.
3. **Non-Destructive Preservation:** Uploads the local version separately under `<name>_conflict_<timestamp>.json` inside the same subfolder, updates queue row status to `SyncStatus.CONFLICT`, and stores `conflictCopyFileId`.
4. **User Resolution:** UI (`DriveVaultScreen`) displays live status badges (`Synced`, `Syncing`, `Pending Offline`, `Conflict`, `Error`) and lets the user resolve via `ConflictResolutionStrategy.KEEP_LOCAL` (overwrites canonical remote file cleanly) or `ConflictResolutionStrategy.KEEP_CLOUD` (discards local queue delta).

## 6. One-Tap Backup Restoration
`AquaDriveSyncCoordinator.restoreSnapshotIntoRoom` atomically writes downloaded `DriveHistorySnapshot` conversations, messages (`toolActionsJson`, `groundingSourcesJson`), and long-term memories back into encrypted Room SSOT tables tagged `SyncStatus.SYNCED`.
