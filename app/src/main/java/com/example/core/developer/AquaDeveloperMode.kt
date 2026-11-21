package com.example.core.developer

import android.util.Base64
import com.example.data.local.SecureConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GitHubRepoSummary(
    val fullName: String,
    val description: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val htmlUrl: String
)

/**
 * Aqua Developer Mode integrating real GitHub REST API v3 calls authenticated via
 * Android Keystore-encrypted GitHub Personal Access Token (never hardcoded or mocked).
 */
class AquaDeveloperModeClient(
    private val secureConfigStore: SecureConfigStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val githubApiBaseUrl: String = "https://api.github.com"
) {
    suspend fun fetchUserRepositories(limit: Int = 20): Result<List<GitHubRepoSummary>> = withContext(Dispatchers.IO) {
        val pat = secureConfigStore.getGithubPat().orEmpty()
        if (pat.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("GitHub Personal Access Token is not configured. Save a PAT in Developer Mode (stored in Android Keystore).")
            )
        }
        runCatching {
            val req = Request.Builder()
                .url("$githubApiBaseUrl/user/repos?sort=updated&per_page=$limit")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Aqua-AI-Android")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub API HTTP ${resp.code}: ${raw.take(240)}")
                }
                val arr = JSONArray(raw)
                (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    GitHubRepoSummary(
                        fullName = o.optString("full_name", ""),
                        description = o.optString("description", "").takeIf { it != "null" }.orEmpty(),
                        defaultBranch = o.optString("default_branch", "main"),
                        isPrivate = o.optBoolean("private", false),
                        htmlUrl = o.optString("html_url", "")
                    )
                }.filter { it.fullName.isNotBlank() }
            }
        }
    }

    suspend fun fetchFileContent(
        ownerAndRepo: String,
        filePath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val pat = secureConfigStore.getGithubPat().orEmpty()
        if (pat.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub PAT is missing."))
        }
        runCatching {
            val cleanRepo = ownerAndRepo.trim().trim('/')
            val cleanPath = filePath.trim().trimStart('/')
            val req = Request.Builder()
                .url("$githubApiBaseUrl/repos/$cleanRepo/contents/$cleanPath")
                .header("Authorization", "Bearer $pat")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Aqua-AI-Android")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub file fetch failed (HTTP ${resp.code}): ${raw.take(200)}")
                }
                val obj = JSONObject(raw)
                val encoded = obj.optString("content", "").replace("\n", "")
                if (encoded.isNotBlank() && obj.optString("encoding") == "base64") {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    raw
                }
            }
        }
    }
}
