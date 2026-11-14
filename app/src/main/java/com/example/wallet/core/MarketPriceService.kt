package com.example.wallet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class MarketQuote(
    val coingeckoId: String,
    val priceUsd: Double,
    val change24hPct: Double?
)

class MarketPriceService(
    private val httpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, MarketQuote>>()

    suspend fun fetchPricesUsd(coingeckoIds: List<String>): Map<String, MarketQuote> =
        withContext(Dispatchers.IO) {
            val distinct = coingeckoIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) return@withContext emptyMap()

            val now = System.currentTimeMillis()
            val out = mutableMapOf<String, MarketQuote>()
            val missing = mutableListOf<String>()

            for (id in distinct) {
                val cached = cache[id]
                if (cached != null && now - cached.first < CACHE_TTL_MS) {
                    out[id] = cached.second
                } else {
                    missing.add(id)
                }
            }

            if (missing.isEmpty()) return@withContext out

            runCatching {
                val joined = missing.joinToString(",")
                val url = "https://api.coingecko.com/api/v3/simple/price?ids=$joined&vs_currencies=usd&include_24hr_change=true"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    for (id in missing) {
                        val obj = json.optJSONObject(id) ?: continue
                        val price = obj.optDouble("usd", Double.NaN)
                        if (!price.isNaN()) {
                            val change = if (obj.has("usd_24h_change") && !obj.isNull("usd_24h_change")) {
                                obj.optDouble("usd_24h_change")
                            } else {
                                null
                            }
                            val quote = MarketQuote(id, price, change)
                            cache[id] = now to quote
                            out[id] = quote
                        }
                    }
                }
            }
            out
        }

    suspend fun fetchSinglePriceUsd(queryOrSymbol: String): Result<MarketQuote> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolved = normalizeCoinId(queryOrSymbol)
                val map = fetchPricesUsd(listOf(resolved))
                map[resolved] ?: throw IllegalStateException("Live market quote unavailable for '$queryOrSymbol'.")
            }
        }

    fun normalizeCoinId(coin: String): String {
        return when (coin.trim().lowercase()) {
            "btc", "tbtc", "bitcoin" -> "bitcoin"
            "eth", "ethereum" -> "ethereum"
            "usdt", "tether" -> "tether"
            "usdc", "usd-coin" -> "usd-coin"
            "bnb", "binancecoin" -> "binancecoin"
            "sol", "solana" -> "solana"
            "pol", "matic", "polygon" -> "polygon-ecosystem-token"
            else -> coin.trim().lowercase()
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 45_000L
    }
}
