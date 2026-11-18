package com.example.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import org.json.JSONObject

/**
 * Origin-restricted bridge allowing Aqua AI's Legal Portal pages (both bundled local assets
 * and https://sras756.github.io/aqua-ai) to authenticate using Android's native Credential
 * Manager / Firebase Auth session instead of triggering Google's WebView OAuth Error 400.
 */
class AquaLegalPortalAuthBridge(
    private val onRequestNativeSignIn: () -> Unit,
    private val onOpenExternal: (String) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var verifiedPortalUrl: String? = null

    fun updateVerifiedUrl(url: String?) {
        verifiedPortalUrl = if (isTrustedAquaPortalUrl(url)) url else null
    }

    @JavascriptInterface
    fun getSignedInUserJson(): String {
        if (!isTrustedAquaPortalUrl(verifiedPortalUrl)) return "{}"
        return currentFirebaseUserJson()
    }

    @JavascriptInterface
    fun requestNativeGoogleSignIn() {
        if (!isTrustedAquaPortalUrl(verifiedPortalUrl)) return
        mainHandler.post { onRequestNativeSignIn() }
    }

    @JavascriptInterface
    fun openExternal(url: String?) {
        val target = url?.trim().orEmpty()
        if (target.startsWith("https://") || target.startsWith("http://")) {
            mainHandler.post { onOpenExternal(target) }
        }
    }

    companion object {
        private const val PORTAL_HOST = "sras756.github.io"
        private const val LOCAL_LEGAL_PREFIX = "file:///android_asset/legal/"

        fun isTrustedAquaPortalUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (url.startsWith(LOCAL_LEGAL_PREFIX)) return true
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path?.lowercase().orEmpty()
            return scheme == "https" &&
                host == PORTAL_HOST &&
                (path == "/aqua-ai" || path.startsWith("/aqua-ai/"))
        }

        fun isGoogleOAuthFlowUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase().orEmpty()
            if (host != "accounts.google.com") return false
            val lower = url.lowercase()
            return lower.contains("/o/oauth2") ||
                lower.contains("/signin/oauth") ||
                lower.contains("/v3/signin") ||
                lower.contains("/gsi/")
        }

        fun isGoogleOriginMismatchErrorUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase()
            return (lower.contains("accounts.google.com") && lower.contains("/signin/oauth/error")) ||
                lower.contains("origin_mismatch") ||
                lower.contains("disallowed_useragent")
        }

        fun currentFirebaseUserJson(): String {
            val user = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return "{}"
            val email = user.email?.trim().orEmpty()
            if (email.isBlank()) return "{}"
            return JSONObject().apply {
                put("sub", user.uid)
                put("name", user.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'))
                put("email", email)
                put("picture", user.photoUrl?.toString().orEmpty())
            }.toString()
        }

        /**
         * Injects verified native Google session & replaces the WebView GSI mount with native
         * Credential Manager Sign-In so both bundled assets and GitHub Pages work inside WebView.
         */
        fun buildPortalSyncScript(pageUrl: String?): String? {
            if (!isTrustedAquaPortalUrl(pageUrl)) return null
            val userJson = currentFirebaseUserJson()
            val quotedUserJson = JSONObject.quote(userJson)
            return """
                (function() {
                  window.__AQUA_NATIVE_BRIDGE__ = true;
                  var USER_KEY = "aqua_google_user";
                  var ACK_KEY = "aqua_legal_ack";
                  var rawUser = $quotedUserJson;
                  var nativeUser = null;
                  try { nativeUser = JSON.parse(rawUser); } catch (_) { nativeUser = null; }

                  function applyNativeMount() {
                    var mount = document.getElementById("gsiButtonMount");
                    if (!mount) return;
                    if (mount.getAttribute("data-aqua-native") === "1" && document.getElementById("aquaNativeSignInBtn")) {
                      return;
                    }
                    mount.setAttribute("data-aqua-native", "1");
                    mount.innerHTML = '';
                    var btn = document.createElement("button");
                    btn.type = "button";
                    btn.id = "aquaNativeSignInBtn";
                    btn.textContent = "G  Sign in with Google (App Verified)";
                    btn.style.cssText = "cursor:pointer;padding:9px 16px;border-radius:999px;border:1px solid rgba(34,211,238,0.65);background:#07192d;color:#eaf6ff;font-weight:700;font-size:0.84rem;display:inline-flex;align-items:center;gap:8px;";
                    btn.addEventListener("click", function() {
                      if (window.AquaAndroidPortal && window.AquaAndroidPortal.requestNativeGoogleSignIn) {
                        window.AquaAndroidPortal.requestNativeGoogleSignIn();
                      }
                    });
                    mount.appendChild(btn);
                  }

                  function syncDomFallback(user) {
                    var signedOutEl = document.getElementById("gsiSignedOutInfo");
                    var signedInEl = document.getElementById("gsiSignedInInfo");
                    var avatarEl = document.getElementById("gsiAvatar");
                    var nameEl = document.getElementById("gsiName");
                    var emailEl = document.getElementById("gsiEmail");
                    var mountEl = document.getElementById("gsiButtonMount");
                    var ackBtn = document.getElementById("ackLegalBtn");
                    var ackBadge = document.getElementById("ackStatusBadge");
                    var signOutBtn = document.getElementById("gsiSignOutBtn");
                    if (!signedOutEl || !signedInEl || !mountEl) return;

                    var ack = null;
                    try { ack = JSON.parse(localStorage.getItem(ACK_KEY) || "null"); } catch (_) {}

                    if (user && user.email) {
                      signedOutEl.style.display = "none";
                      signedInEl.style.display = "flex";
                      if (nameEl) nameEl.textContent = user.name || "Google Account";
                      if (emailEl) emailEl.textContent = user.email;
                      if (avatarEl) {
                        if (user.picture) {
                          avatarEl.src = user.picture;
                          avatarEl.style.display = "block";
                        } else {
                          avatarEl.style.display = "none";
                        }
                      }
                      mountEl.style.display = "none";
                      if (signOutBtn) signOutBtn.style.display = "inline-flex";
                      if (ack && ack.email === user.email && ack.timestamp) {
                        if (ackBtn) ackBtn.style.display = "none";
                        if (ackBadge) {
                          ackBadge.style.display = "inline-block";
                          ackBadge.textContent = "✓ Policy Verified • " + ack.timestamp;
                        }
                      } else {
                        if (ackBtn) ackBtn.style.display = "inline-flex";
                        if (ackBadge) ackBadge.style.display = "none";
                      }
                    } else {
                      signedOutEl.style.display = "flex";
                      signedInEl.style.display = "none";
                      mountEl.style.display = "block";
                      if (ackBtn) ackBtn.style.display = "none";
                      if (ackBadge) ackBadge.style.display = "none";
                      if (signOutBtn) signOutBtn.style.display = "none";
                      applyNativeMount();
                    }
                  }

                  window.__aquaPortalNativeSignInSuccess = function(jsonText) {
                    var parsed = null;
                    try { parsed = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText; } catch (_) {}
                    if (parsed && parsed.email) {
                      localStorage.setItem(USER_KEY, JSON.stringify(parsed));
                      syncDomFallback(parsed);
                      if (typeof window.__aquaOnPortalAuthChanged === "function") {
                        window.__aquaOnPortalAuthChanged();
                      }
                    }
                  };

                  if (nativeUser && nativeUser.email) {
                    localStorage.setItem(USER_KEY, JSON.stringify(nativeUser));
                    syncDomFallback(nativeUser);
                  } else {
                    var stored = null;
                    try { stored = JSON.parse(localStorage.getItem(USER_KEY) || "null"); } catch (_) {}
                    if (!stored || !stored.email) {
                      applyNativeMount();
                      var mountEl = document.getElementById("gsiButtonMount");
                      if (mountEl && window.MutationObserver && !mountEl.__aquaObserved) {
                        mountEl.__aquaObserved = true;
                        new MutationObserver(function() {
                          var cur = null;
                          try { cur = JSON.parse(localStorage.getItem(USER_KEY) || "null"); } catch (_) {}
                          if (!cur || !cur.email) applyNativeMount();
                        }).observe(mountEl, { childList: true });
                      }
                    } else {
                      syncDomFallback(stored);
                    }
                  }
                  if (typeof window.__aquaOnPortalAuthChanged === "function") {
                    window.__aquaOnPortalAuthChanged();
                  }
                })();
            """.trimIndent()
        }
    }
}
