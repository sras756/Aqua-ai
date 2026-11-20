package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.core.database.AgentTaskDao
import com.example.core.database.AgentTaskEntity
import com.example.core.database.AiUsageMeterDao
import com.example.core.database.AiUsageMeterEntity
import com.example.core.database.AuditLogDao
import com.example.core.database.AuditLogEntity
import com.example.core.database.AutomationWorkflowDao
import com.example.core.database.AutomationWorkflowEntity
import com.example.core.database.BookmarkDao
import com.example.core.database.BookmarkEntity
import com.example.core.database.ChatMessageEntity
import com.example.core.database.ConversationDao
import com.example.core.database.ConversationEntity
import com.example.core.database.DriveFileMetadataDao
import com.example.core.database.DriveFileMetadataEntity
import com.example.core.database.DriveSyncQueueDao
import com.example.core.database.DriveSyncQueueEntity
import com.example.core.database.MemoryDao
import com.example.core.database.MemoryEntity
import com.example.core.database.MemoryFtsEntity
import com.example.core.database.WorkspaceDocumentDao
import com.example.core.database.WorkspaceDocumentEntity
import com.example.core.database.WorkspaceDocumentFtsEntity
import com.example.core.security.AquaDatabaseKeyManager
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "device_action_logs")
data class DeviceActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val toolName: String,
    val status: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "wallet_transactions")
data class CachedWalletTxEntity(
    @PrimaryKey val txHash: String,
    val idempotencyKey: String,
    val walletId: String,
    val networkId: String,
    val networkName: String,
    val fromAddress: String,
    val toAddress: String,
    val assetSymbol: String,
    val amountDisplay: String,
    val feeDisplay: String,
    val status: String,
    val confirmations: Int,
    val timestampMs: Long,
    val explorerUrl: String,
    val direction: String,
    val errorMessage: String? = null
)

@Entity(tableName = "wallet_custom_tokens")
data class WalletCustomTokenEntity(
    @PrimaryKey val id: String,
    val networkId: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val contractOrMintAddress: String,
    val coingeckoId: String,
    val enabled: Boolean = true
)

@Entity(tableName = "wallet_custom_networks")
data class WalletCustomNetworkEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nativeSymbol: String,
    val chainId: Long,
    val rpcUrl: String,
    val explorerBaseUrl: String,
    val isTestnet: Boolean = false
)

@Entity(tableName = "wallet_address_book")
data class WalletAddressBookEntity(
    @PrimaryKey val id: String,
    val label: String,
    val chainFamily: String,
    val address: String,
    val note: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DeviceActionLogDao {
    @Query("SELECT * FROM device_action_logs WHERE userId = :userId ORDER BY timestampMs DESC LIMIT 50")
    fun observeLogsForUser(userId: String): Flow<List<DeviceActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: DeviceActionLogEntity)

    @Query("DELETE FROM device_action_logs WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}

@Dao
interface WalletCacheDao {
    @Query("SELECT * FROM wallet_transactions WHERE walletId = :walletId ORDER BY timestampMs DESC LIMIT 100")
    fun observeTransactionsForWallet(walletId: String): Flow<List<CachedWalletTxEntity>>

    @Query("SELECT * FROM wallet_transactions WHERE walletId = :walletId ORDER BY timestampMs DESC LIMIT 100")
    suspend fun getTransactionsForWallet(walletId: String): List<CachedWalletTxEntity>

    @Query("SELECT * FROM wallet_transactions WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): CachedWalletTxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(tx: CachedWalletTxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(txs: List<CachedWalletTxEntity>)

    @Query("SELECT * FROM wallet_custom_tokens")
    fun observeCustomTokens(): Flow<List<WalletCustomTokenEntity>>

    @Query("SELECT * FROM wallet_custom_tokens")
    suspend fun getCustomTokens(): List<WalletCustomTokenEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomToken(token: WalletCustomTokenEntity)

    @Query("DELETE FROM wallet_custom_tokens WHERE id = :tokenId")
    suspend fun deleteCustomToken(tokenId: String)

    @Query("SELECT * FROM wallet_custom_networks")
    fun observeCustomNetworks(): Flow<List<WalletCustomNetworkEntity>>

    @Query("SELECT * FROM wallet_custom_networks")
    suspend fun getCustomNetworks(): List<WalletCustomNetworkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomNetwork(network: WalletCustomNetworkEntity)

    @Query("DELETE FROM wallet_custom_networks WHERE id = :networkId")
    suspend fun deleteCustomNetwork(networkId: String)

    @Query("SELECT * FROM wallet_address_book ORDER BY label COLLATE NOCASE ASC")
    fun observeAddressBook(): Flow<List<WalletAddressBookEntity>>

    @Query("SELECT * FROM wallet_address_book ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAddressBook(): List<WalletAddressBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddressBookEntry(entry: WalletAddressBookEntity)

    @Query("DELETE FROM wallet_address_book WHERE id = :entryId")
    suspend fun deleteAddressBookEntry(entryId: String)
}

@Database(
    entities = [
        DeviceActionLogEntity::class,
        CachedWalletTxEntity::class,
        WalletCustomTokenEntity::class,
        WalletCustomNetworkEntity::class,
        WalletAddressBookEntity::class,
        ConversationEntity::class,
        ChatMessageEntity::class,
        MemoryEntity::class,
        MemoryFtsEntity::class,
        WorkspaceDocumentEntity::class,
        WorkspaceDocumentFtsEntity::class,
        BookmarkEntity::class,
        AgentTaskEntity::class,
        AuditLogEntity::class,
        DriveSyncQueueEntity::class,
        DriveFileMetadataEntity::class,
        AiUsageMeterEntity::class,
        AutomationWorkflowEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AquaCacheDatabase : RoomDatabase() {
    abstract fun deviceActionLogDao(): DeviceActionLogDao
    abstract fun walletCacheDao(): WalletCacheDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun workspaceDocumentDao(): WorkspaceDocumentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun driveSyncQueueDao(): DriveSyncQueueDao
    abstract fun driveFileMetadataDao(): DriveFileMetadataDao
    abstract fun aiUsageMeterDao(): AiUsageMeterDao
    abstract fun automationWorkflowDao(): AutomationWorkflowDao

    companion object {
        const val ENCRYPTED_DB_NAME = "aqua_ssot_encrypted.db"
        private const val LEGACY_PLAINTEXT_DB_NAME = "aqua_local_cache.db"

        @Volatile
        private var INSTANCE: AquaCacheDatabase? = null

        fun getInstance(context: Context): AquaCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun createInMemoryForTesting(context: Context): AquaCacheDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AquaCacheDatabase::class.java
            ).allowMainThreadQueries().build()
        }

        private fun buildDatabase(appContext: Context): AquaCacheDatabase {
            runCatching { appContext.deleteDatabase(LEGACY_PLAINTEXT_DB_NAME) }
            val builder = Room.databaseBuilder(
                appContext,
                AquaCacheDatabase::class.java,
                ENCRYPTED_DB_NAME
            ).fallbackToDestructiveMigration(dropAllTables = true)

            if (AquaDatabaseKeyManager.isSqlCipherNativeAvailable()) {
                val passphrase = AquaDatabaseKeyManager.getOrCreateDatabasePassphrase(appContext)
                builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
            } else if (!AquaDatabaseKeyManager.isRobolectricEnvironment()) {
                throw SecurityException("SQLCipher native library unavailable; refusing to open unencrypted SSOT database.")
            }

            return builder.build()
        }
    }
}
