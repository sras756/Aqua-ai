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

    private val chartCache = ConcurrentHashMap<String, Pair<Long, MarketSparklineData>>()
    private val fiatRateCache = ConcurrentHashMap<String, Pair<Long, Double>>()

    suspend fun fetchFiatRateFromUsd(currency: DisplayFiatCurrency): Result<Double> =
        withContext(Dispatchers.IO) {
            if (currency == DisplayFiatCurrency.USD) return@withContext Result.success(1.0)
            val now = System.currentTimeMillis()
            fiatRateCache[currency.code]?.let { (ts, rate) ->
                if (now - ts < CACHE_TTL_MS * 2) return@withContext Result.success(rate)
            }
            runCatching {
                val url = "https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies=usd,${currency.coingeckoKey}"
                val req = Request.Builder().url(url).header("Accept", "application/json").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("CoinGecko FX HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw IllegalStateException("Empty FX body")
                    val obj = JSONObject(body).optJSONObject("tether")
                        ?: throw IllegalStateException("FX quote unavailable")
                    val usd = obj.optDouble("usd", 1.0).takeIf { !it.isNaN() && it > 0.0 } ?: 1.0
                    val target = obj.optDouble(currency.coingeckoKey, Double.NaN)
                    require(!target.isNaN() && target > 0.0) { "Currency ${currency.code} rate unavailable." }
                    val ratio = target / usd
                    fiatRateCache[currency.code] = now to ratio
                    ratio
                }
            }
        }

    suspend fun fetchMarketChart(
        coingeckoId: String,
        symbol: String,
        days: Int = 7
    ): Result<MarketSparklineData> = withContext(Dispatchers.IO) {
        val normalizedId = normalizeCoinId(coingeckoId)
        val cacheKey = "${normalizedId}_$days"
        val now = System.currentTimeMillis()
        chartCache[cacheKey]?.let { (ts, data) ->
            if (now - ts < CHART_CACHE_TTL_MS) return@withContext Result.success(data)
        }
        runCatching {
            val url = "https://api.coingecko.com/api/v3/coins/$normalizedId/market_chart?vs_currency=usd&days=$days"
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("CoinGecko chart HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw IllegalStateException("Empty chart body")
                val json = JSONObject(body)
                val pricesArr = json.optJSONArray("prices")
                    ?: throw IllegalStateException("No historical price series returned.")
                val rawPoints = ArrayList<MarketChartPoint>(pricesArr.length())
                for (i in 0 until pricesArr.length()) {
                    val pair = pricesArr.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    val ts = pair.optLong(0, 0L)
                    val px = pair.optDouble(1, Double.NaN)
                    if (ts > 0L && !px.isNaN() && px > 0.0) {
                        rawPoints.add(MarketChartPoint(ts, px))
                    }
                }
                require(rawPoints.size >= 2) { "Insufficient chart points for $symbol." }
                // Downsample cleanly to <= 48 points for crisp Canvas rendering
                val step = (rawPoints.size / 48).coerceAtLeast(1)
                val sampled = rawPoints.filterIndexed { idx, _ -> idx % step == 0 || idx == rawPoints.lastIndex }
                val first = sampled.first().priceUsd
                val last = sampled.last().priceUsd
                val changePct = if (first > 0.0) ((last - first) / first) * 100.0 else 0.0
                val high = sampled.maxOf { it.priceUsd }
                val low = sampled.minOf { it.priceUsd }
                val data = MarketSparklineData(
                    coingeckoId = normalizedId,
                    symbol = symbol.uppercase(),
                    days = days,
                    currentPriceUsd = last,
                    periodChangePct = changePct,
                    highUsd = high,
                    lowUsd = low,
                    points = sampled
                )
                chartCache[cacheKey] = now to data
                data
            }
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
        const val CHART_CACHE_TTL_MS = 120_000L
    }
}
