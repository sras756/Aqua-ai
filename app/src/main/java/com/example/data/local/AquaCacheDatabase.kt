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
import kotlinx.coroutines.flow.Flow

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
}

@Database(
    entities = [
        DeviceActionLogEntity::class,
        CachedWalletTxEntity::class,
        WalletCustomTokenEntity::class,
        WalletCustomNetworkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AquaCacheDatabase : RoomDatabase() {
    abstract fun deviceActionLogDao(): DeviceActionLogDao
    abstract fun walletCacheDao(): WalletCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AquaCacheDatabase? = null

        fun getInstance(context: Context): AquaCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AquaCacheDatabase::class.java,
                    "aqua_local_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}
