package com.example.core

import android.app.Activity
import android.view.WindowManager
import com.example.core.security.AquaSecurityGuardian
import com.example.wallet.security.WalletScreenSecurityHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase8WalletSecurityTest {

    @Test
    fun `flag secure sets and clears window layout params cleanly on host activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)

        val applied = WalletScreenSecurityHelper.applyFlagSecure(activity, true)
        assertTrue(applied)
        assertTrue((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)

        WalletScreenSecurityHelper.applyFlagSecure(activity, false)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `security guardian flags phishing urls burn addresses and unlimited erc20 approvals`() {
        val phish = AquaSecurityGuardian.inspectUrl("https://verify-wallet.example.com/claim")
        assertTrue(phish.isHighRisk)
        assertEquals("PHISHING_HEURISTIC", phish.category)

        val puny = AquaSecurityGuardian.inspectUrl("https://xn--80ak6aa92e.com/")
        assertTrue(puny.isHighRisk)
        assertEquals("IDN_HOMOGRAPH", puny.category)

        val cleanUrl = AquaSecurityGuardian.inspectUrl("https://ethereum.org/en/")
        assertFalse(cleanUrl.isHighRisk)

        val burn = AquaSecurityGuardian.inspectWalletTransfer(
            fromAddress = "0x1111111111111111111111111111111111111111",
            toAddress = "0x000000000000000000000000000000000000dead"
        )
        assertTrue(burn.isHighRisk)
        assertEquals("BURN_ADDRESS", burn.category)

        val unlimitedApprove = AquaSecurityGuardian.inspectWalletTransfer(
            fromAddress = "0x1111111111111111111111111111111111111111",
            toAddress = "0x2222222222222222222222222222222222222222",
            calldataHex = "0x095ea7b30000000000000000000000002222222222222222222222222222222222222222ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        )
        assertTrue(unlimitedApprove.isHighRisk)
        assertEquals("UNLIMITED_TOKEN_APPROVAL", unlimitedApprove.category)
    }
}
