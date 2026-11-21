package com.example.core.security

import com.example.core.browser.DownloadRiskVerdict
import com.example.core.browser.PromptInjectionFirewall
import java.net.URI

data class SecurityThreatAlert(
    val isHighRisk: Boolean,
    val category: String,
    val title: String,
    val detail: String
)

/**
 * Centralized Aqua Security Guardian covering phishing URLs, APK/executable download risks,
 * and blockchain address/contract approval scam checks for the Wallet & Browser.
 */
object AquaSecurityGuardian {

    private val suspiciousUrlHosts = setOf(
        "bit.ly", "tinyurl.com", "is.gd", "t.co"
    )

    private val phishingKeywords = listOf(
        "airdrop-claim", "verify-wallet", "seed-restore", "connect-drainer",
        "free-eth", "free-btc", "urgent-kyc", "metamask-sync", "phantom-restore"
    )

    private val knownBurnAddresses = setOf(
        "0x0000000000000000000000000000000000000000",
        "0x000000000000000000000000000000000000dead",
        "11111111111111111111111111111111"
    )

    fun inspectUrl(url: String): SecurityThreatAlert {
        val clean = url.trim()
        val uri = runCatching { URI(clean) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val lowerUrl = clean.lowercase()

        if (scheme !in setOf("https", "http")) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "UNSAFE_SCHEME",
                title = "Blocked Unsafe URI Scheme",
                detail = "Scheme '$scheme://' is restricted to prevent local file or JavaScript code execution."
            )
        }
        if (host.startsWith("xn--") || clean.any { it.code > 127 && host.contains(it) }) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "IDN_HOMOGRAPH",
                title = "Punycode / Homograph Domain Warning",
                detail = "Domain '$host' uses IDN punycode characters often abused for lookalike phishing."
            )
        }
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "RAW_IP_URL",
                title = "Raw IP Address Host",
                detail = "Direct IP navigation ($host) lacks verified domain identity."
            )
        }
        val keyword = phishingKeywords.firstOrNull { lowerUrl.contains(it) }
        if (keyword != null) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "PHISHING_HEURISTIC",
                title = "Potential Wallet/Credential Phishing",
                detail = "URL contains high-risk lure pattern '$keyword'. Never enter recovery phrases online."
            )
        }
        if (scheme == "http") {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "CLEARTEXT_HTTP",
                title = "Unencrypted HTTP Connection",
                detail = "Traffic to '$host' is unencrypted. Avoid signing in or submitting sensitive data."
            )
        }
        if (host in suspiciousUrlHosts) {
            return SecurityThreatAlert(
                isHighRisk = false,
                category = "URL_SHORTENER",
                title = "Shortened Link Notice",
                detail = "Inspect expanded destination domain before connecting accounts."
            )
        }
        return SecurityThreatAlert(
            isHighRisk = false,
            category = "CLEAN",
            title = "URL Verified",
            detail = "HTTPS transport to '$host'."
        )
    }

    fun inspectDownload(url: String, contentDisposition: String?, mimeType: String?): DownloadRiskVerdict =
        PromptInjectionFirewall.evaluateDownloadRisk(url, contentDisposition, mimeType)

    fun inspectWalletTransfer(
        fromAddress: String,
        toAddress: String,
        calldataHex: String = ""
    ): SecurityThreatAlert {
        val cleanDest = toAddress.trim()
        val lowerDest = cleanDest.lowercase()

        if (lowerDest in knownBurnAddresses) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "BURN_ADDRESS",
                title = "Burn Address Detected",
                detail = "Destination '$cleanDest' is a known irreversible burn address. Transfer blocked."
            )
        }
        if (cleanDest.equals(fromAddress.trim(), ignoreCase = true) && cleanDest.isNotBlank()) {
            return SecurityThreatAlert(
                isHighRisk = false,
                category = "SELF_TRANSFER",
                title = "Self-Transfer Notice",
                detail = "Sender and recipient addresses are identical."
            )
        }
        val cleanData = calldataHex.lowercase().removePrefix("0x")
        // ERC-20 approve(address,uint256) selector = 095ea7b3 with max uint256 ffff...ffff
        if (cleanData.startsWith("095ea7b3") && cleanData.endsWith("ffffffffffffffffffffffffffffffff")) {
            return SecurityThreatAlert(
                isHighRisk = true,
                category = "UNLIMITED_TOKEN_APPROVAL",
                title = "Unlimited ERC-20 Spender Approval",
                detail = "This contract call grants unlimited spending allowance. Verify spender trust carefully."
            )
        }
        return SecurityThreatAlert(
            isHighRisk = false,
            category = "CLEAN",
            title = "Recipient Address Cleared",
            detail = "No burn address or unlimited approval trap detected."
        )
    }
}
