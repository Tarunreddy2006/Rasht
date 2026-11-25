package com.personalfinance.app

import com.personalfinance.app.collection.ParserRegistry
import com.personalfinance.app.collection.TransactionNotificationGuard
import com.personalfinance.app.model.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionNotificationGuardTest {
    @Test
    fun promotionalRupeeAmountsAreRejectedForEverySupportedUpiApp() {
        val cases = listOf(
            Triple("com.google.android.apps.nbu.paisa.user", "Google Pay Offers", "Get ₹250 cashback on your next payment. Claim now."),
            Triple("com.phonepe.app", "PhonePe Rewards", "Offer worth ₹100 unlocked! Recharge your mobile now to claim."),
            Triple("net.one97.paytm", "Paytm Offers", "Win up to ₹500 when you pay your electricity bill this week!"),
            Triple("in.org.npci.upiapp", "BHIM Rewards", "Receive Rs. 150 as a reward when you make your next UPI payment.")
        )

        for ((packageName, title, body) in cases) {
            assertNull("Promotional message must not parse: $body",
                ParserRegistry.parse(packageName, title, body, null))
        }
    }

    @Test
    fun amountOnlyAndPromoNotificationsAreNotTransactions() {
        assertNull(ParserRegistry.parse(
            "com.google.android.apps.nbu.paisa.user", "Special offer", "₹999 value unlocked", null))
        assertNull(ParserRegistry.parse(
            "net.one97.paytm", "You received ₹500", "Cashback offer available. Tap to claim.", null))
        assertFalse(TransactionNotificationGuard.hasExplicitTransactionEvidence("Get Rs. 500 today"))
    }

    @Test
    fun validCompletedUpiTransactionsStillParse() {
        val cases = listOf(
            Triple("com.google.android.apps.nbu.paisa.user", "Google Pay", "Paid ₹180.00 to Chai Point"),
            Triple("com.phonepe.app", "PhonePe", "Payment of ₹1,499.00 to Swiggy was successful. UTR: 432198765432"),
            Triple("net.one97.paytm", "Paytm", "Paid ₹240 to Sharma Grocery Store successfully"),
            Triple("in.org.npci.upiapp", "BHIM", "Payment of Rs. 350.00 to Coffee Day successful. Ref: 987654321012")
        )

        for ((packageName, title, body) in cases) {
            val parsed = ParserRegistry.parse(packageName, title, body, null)
            assertNotNull("Valid transaction should parse: $body", parsed)
            assertTrue(parsed!!.amountPaise > 0L)
            assertTrue(parsed.direction != TransactionDirection.UNKNOWN)
        }
    }

    @Test
    fun bankSmsNeedsExplicitMovementPhrase() {
        val bankSms = "A/c *4589 debited for Rs 3,200.00 on 08-NOV-26 by UPI:ref 432109876543 to Croma"
        val parsed = ParserRegistry.parse("com.google.android.apps.messaging", "HDFC Bank", bankSms, null)
        assertNotNull(parsed)
        assertEquals(TransactionDirection.DEBIT, parsed!!.direction)

        assertNull(ParserRegistry.parse(
            "com.google.android.apps.messaging", "HDFC Bank offer", "Earn Rs 500 cashback with our card", null))
    }
}
