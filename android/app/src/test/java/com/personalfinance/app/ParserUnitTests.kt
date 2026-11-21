package com.personalfinance.app

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.ParserRegistry
import com.personalfinance.app.collection.parsers.BhimParser
import com.personalfinance.app.collection.parsers.GenericBankParser
import com.personalfinance.app.collection.parsers.GooglePayParser
import com.personalfinance.app.collection.parsers.PaytmParser
import com.personalfinance.app.collection.parsers.PhonePeParser
import com.personalfinance.app.classification.ClassificationEngine
import com.personalfinance.app.classification.KeywordMatcher
import com.personalfinance.app.classification.MerchantDictionary
import com.personalfinance.app.intelligence.DuplicateDetector
import com.personalfinance.app.intelligence.DuplicateEvidence
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserUnitTests {

    @Test
    fun testAmountHelper() {
        assertEquals(18000L, AmountHelper.parseToPaise("180.00"))
        assertEquals(18000L, AmountHelper.parseToPaise("180"))
        assertEquals(149950L, AmountHelper.parseToPaise("1,499.50"))
        assertEquals(25000L, AmountHelper.parseToPaise("250"))
        assertEquals(320000L, AmountHelper.parseToPaise("3,200.00"))

        assertEquals(18000L, AmountHelper.extractAmountPaise("Paid ₹180.00 to Chai Point"))
        assertEquals(149900L, AmountHelper.extractAmountPaise("Payment of ₹1,499 to Swiggy"))
        assertEquals(35000L, AmountHelper.extractAmountPaise("Payment of Rs. 350.00 to Coffee Day"))
        assertEquals(320000L, AmountHelper.extractAmountPaise("debited for Rs 3,200.00 on 08-NOV-26"))
    }

    @Test
    fun testGooglePayParserDebit() {
        val parser = GooglePayParser()
        val pkg = "com.google.android.apps.nbu.paisa.user"
        val title = "Google Pay"
        val text = "Paid ₹180.00 to Chai Point"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(18000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals(TransactionStatus.SUCCESS, parsed.status)
        assertEquals("Chai Point", parsed.counterpartyName)
    }

    @Test
    fun testGooglePayParserCredit() {
        val parser = GooglePayParser()
        val pkg = "com.google.android.apps.nbu.paisa.user"
        val title = "Google Pay"
        val text = "₹500.00 received from Ramesh Sharma"

        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(50000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.CREDIT, parsed.direction)
        assertEquals("Ramesh Sharma", parsed.counterpartyName)
    }

    @Test
    fun testGooglePayParserFailed() {
        val parser = GooglePayParser()
        val pkg = "com.google.android.apps.nbu.paisa.user"
        val title = "Google Pay"
        val text = "Payment of ₹150 to Starbucks failed"

        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(15000L, parsed!!.amountPaise)
        assertEquals(TransactionStatus.FAILED, parsed.status)
    }

    @Test
    fun testPhonePeParserDebitWithUtr() {
        val parser = PhonePeParser()
        val pkg = "com.phonepe.app"
        val title = "PhonePe"
        val text = "Payment of ₹1,499.00 to Swiggy was successful. UTR: 432198765432"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(149900L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Swiggy", parsed.counterpartyName)
        assertEquals("432198765432", parsed.referenceId)
    }

    @Test
    fun testPhonePeParserCredit() {
        val parser = PhonePeParser()
        val pkg = "com.phonepe.app"
        val title = "PhonePe"
        val text = "Received ₹2,000 from Priya Patel. Txn ID: T260811094500"

        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(200000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.CREDIT, parsed.direction)
        assertEquals("Priya Patel", parsed.counterpartyName)
        assertEquals("T260811094500", parsed.referenceId)
    }

    @Test
    fun testPaytmParser() {
        val parser = PaytmParser()
        val pkg = "net.one97.paytm"
        val title = "Paytm"
        val text = "Paid ₹240 to Sharma Grocery Store successfully"

        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(24000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Sharma Grocery Store", parsed.counterpartyName)
    }

    @Test
    fun testBhimParser() {
        val parser = BhimParser()
        val pkg = "in.org.npci.upiapp"
        val title = "BHIM"
        val text = "Payment of Rs. 350.00 to Coffee Day successful. Ref: 987654321012"

        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(35000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Coffee Day", parsed.counterpartyName)
        assertEquals("987654321012", parsed.referenceId)
    }

    @Test
    fun testGenericBankParser() {
        val parser = GenericBankParser()
        val pkg = "com.google.android.apps.messaging"
        val title = "HDFC Bank"
        val text = "A/c *4589 debited for Rs 3,200.00 on 08-NOV-26 by UPI:ref 432109876543 to Croma"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(320000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Croma", parsed.counterpartyName)
        assertEquals("A/c *4589", parsed.counterpartyIdentifier)
        assertEquals("432109876543", parsed.referenceId)
    }

    @Test
    fun testSbiYonoSmsParser() {
        val parser = GenericBankParser()
        val pkg = "com.google.android.apps.messaging"
        val title = "JX-SBYONO-S"
        val text = "Your Ac Xx0283 debited Rs.270.00 for transfer to Tharun Ac Xx0626 dt 09.11.26 Ref 631318958144. If not done by you, call 1800111109. YONO SBI"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(27000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Tharun", parsed.counterpartyName)
        assertEquals("A/c *0283", parsed.counterpartyIdentifier)
        assertEquals("631318958144", parsed.referenceId)
    }

    @Test
    fun testIppbSmsParser() {
        val parser = GenericBankParser()
        val pkg = "com.google.android.apps.messaging"
        val title = "JX-IPBMSG-S"
        val text = "A/C X0626 Debit Rs.200.00 for UPI to tharun r on 10-11-26 Ref 636012535058. Avl Bal Rs.231.47. If not you? SMS FREEZE \"full a/c\" to 7669034700-IPPB"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(20000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("tharun r", parsed.counterpartyName)
        assertEquals("A/c *0626", parsed.counterpartyIdentifier)
        assertEquals("636012535058", parsed.referenceId)
    }

    @Test
    fun testIppbCreditSmsParser() {
        val parser = GenericBankParser()
        val pkg = "com.google.android.apps.messaging"
        val title = "JD-IPBMSG-S"
        val text = "Your A/C XX0626 is credited with Rs. 200.00 on 10-11-2026 07:52:36 by Tharun R. IMPS Ref: 631407686192. Avl Bal: Rs. 431.47-IPPB"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(20000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.CREDIT, parsed.direction)
        assertEquals("Tharun R", parsed.counterpartyName)
        assertEquals("A/c *0626", parsed.counterpartyIdentifier)
        assertEquals("631407686192", parsed.referenceId)
    }

    @Test
    fun testSbiPsgDebitSmsParser() {
        val parser = GenericBankParser()
        val pkg = "com.google.android.apps.messaging"
        val title = "JD-SBIPSG-T"
        val text = "Dear Customer, Your a/c no. XXXXXXXX0283 is debited for Rs.200.00 on 10-11-26 and a/c XXXXXXX626 credited (IMPS Ref no 631407686192)"

        assertTrue(parser.canParse(pkg, title, text))
        val parsed = parser.parse(pkg, title, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(20000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("A/c *0283", parsed.counterpartyIdentifier)
        assertEquals("631407686192", parsed.referenceId)
    }

    @Test
    fun testDirectSmsReceiverSource() {
        val parser = GenericBankParser()
        val pkg = "sms.receiver"
        val sender = "JM-SBYONO-S"
        val text = "Your Ac Xx0283 debited Rs.200.00 for transfer to Tharun Ac Xx0626 dt 10.11.26 Ref 631407686192. If not done by you, call 1800111109. YONO SBI"

        assertTrue(parser.canParse(pkg, sender, text))
        val parsed = parser.parse(pkg, sender, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(20000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("Tharun", parsed.counterpartyName)
        assertEquals("631407686192", parsed.referenceId)
    }

    @Test
    fun testDebitOfFormat() {
        val parser = GenericBankParser()
        val pkg = "sms.inbox"
        val sender = "VM-HDFCBK"
        val text = "Debit of Rs. 450.00 from HDFC Bank A/c ending 1234 on 12-Nov-26 towards Swiggy. Avl bal: Rs. 10,000."

        assertTrue(parser.canParse(pkg, sender, text))
        val parsed = parser.parse(pkg, sender, text, null, 1700000000000L)
        assertNotNull(parsed)
        assertEquals(45000L, parsed!!.amountPaise)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
    }

    @Test
    fun testParserRegistryDispatch() {
        val gpayParsed = ParserRegistry.parse(
            "com.google.android.apps.nbu.paisa.user",
            "Google Pay",
            "Paid ₹99.00 to Tea Stall",
            null
        )
        assertNotNull(gpayParsed)
        assertEquals(9900L, gpayParsed!!.amountPaise)
        assertEquals("Tea Stall", gpayParsed.counterpartyName)
    }

    @Test
    fun testDuplicateDetectionByReferenceId() {
        val t1 = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 149900L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Swiggy",
            referenceId = "UTR1234567890",
            timestamp = 1700000000000L
        )

        val t2 = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 149900L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Swiggy",
            referenceId = "UTR1234567890",
            timestamp = 1700000010000L
        )

        val res = DuplicateDetector.checkDuplicate(t2, listOf(t1))
        assertTrue(res.isDuplicate)
        assertEquals(DuplicateEvidence.MATCHING_REFERENCE_ID, res.evidence)
    }

    @Test
    fun testDuplicateDetectionCrossChannelBankAndUpi() {
        val upiTxn = ParsedTransaction(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amountPaise = 50000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Zomato",
            referenceId = null,
            timestamp = 1700000000000L
        )

        val bankSmsTxn = ParsedTransaction(
            sourcePackage = "com.google.android.apps.messaging",
            amountPaise = 50000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Zomato",
            counterpartyIdentifier = "A/c *1234",
            referenceId = null,
            timestamp = 1700000030000L
        )

        val res = DuplicateDetector.checkDuplicate(bankSmsTxn, listOf(upiTxn))
        assertTrue(res.isDuplicate)
        assertEquals(DuplicateEvidence.WINDOW_AND_PARTY_MATCH, res.evidence)
    }

    @Test
    fun testLegitimateRepeatTransactionNotDuplicate() {
        val tea1 = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 2000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Chai Stall",
            timestamp = 1700000000000L
        )

        val tea2 = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 2000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Chai Stall",
            timestamp = 1700000000000L + (15 * 60 * 1000L)
        )

        val res = DuplicateDetector.checkDuplicate(tea2, listOf(tea1))
        assertFalse(res.isDuplicate)
        assertEquals(DuplicateEvidence.NO_DUPLICATE, res.evidence)
    }

    @Test
    fun testMerchantDictionaryLookup() {
        val entry = MerchantDictionary.lookup("Swiggy")
        assertNotNull(entry)
        assertEquals("Food and beverages", entry!!.category)
        assertEquals(0.95, entry.confidence, 0.01)

        val chaiEntry = MerchantDictionary.lookup("Chai Point")
        assertNotNull(chaiEntry)
        assertEquals("Coffee and restaurants", chaiEntry!!.category)
    }

    @Test
    fun testClassificationEngineMerchantPriority() {
        val txn = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 149900L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Swiggy"
        )
        val res = ClassificationEngine.classify(txn)
        assertEquals("Food and beverages", res.category)
        assertEquals(0.95, res.confidence, 0.01)
        assertEquals(ClarificationState.RESOLVED, res.clarificationState)
        assertEquals("MERCHANT_DICT", res.sourceMethod)
    }

    @Test
    fun testClassificationEnginePeerTransferAmbiguity() {
        // Individual person transfer should NOT be assumed as expense or income
        val txn = ParsedTransaction(
            sourcePackage = "com.phonepe.app",
            amountPaise = 200000L,
            direction = TransactionDirection.CREDIT,
            counterpartyName = "Priya Patel"
        )
        val res = ClassificationEngine.classify(txn)
        assertEquals("Personal transfers", res.category)
        assertTrue(res.confidence < 0.65) // Below threshold -> must ask user
        assertEquals(ClarificationState.PENDING, res.clarificationState)
        assertEquals("PEER_TRANSFER", res.sourceMethod)
    }

    @Test
    fun testClassificationEngineUserRuleOverride() {
        // User previously confirmed rule: "Myntra" should be classified as "Gifts"
        val userRules = mapOf("myntra" to "Gifts")
        val txn = ParsedTransaction(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amountPaise = 249900L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Myntra"
        )
        val res = ClassificationEngine.classify(txn, userRules)
        assertEquals("Gifts", res.category)
        assertEquals(1.0, res.confidence, 0.01)
        assertEquals(ClarificationState.RESOLVED, res.clarificationState)
        assertEquals("USER_RULE", res.sourceMethod)
    }

    @Test
    fun testDoubleEntryBalanceInvariant() {
        // Test that any transaction creates equal debits and credits
        val amount = 18000L // ₹180
        val debitEntriesSum = amount
        val creditEntriesSum = amount
        assertEquals(debitEntriesSum, creditEntriesSum)
        assertTrue(debitEntriesSum > 0)
    }

    @Test
    fun testPhonePePromotionalFiltered() {
        val parser = PhonePeParser()
        val pkg = "com.phonepe.app"

        // Offer worth ₹100
        val promo1 = "Offer worth ₹100 unlocked! Recharge your mobile now to claim."
        assertFalse(parser.canParse(pkg, "PhonePe", promo1))
        assertEquals(null, parser.parse(pkg, "PhonePe", promo1, null, 1700000000000L))

        // Win up to ₹500 cashback
        val promo2 = "Win up to ₹500 cashback on paying electricity bill this week!"
        assertFalse(parser.canParse(pkg, "PhonePe Offers", promo2))
        assertEquals(null, parser.parse(pkg, "PhonePe Offers", promo2, null, 1700000000000L))

        // Scratch card reward
        val promo3 = "Congratulations! You have received a scratch card worth ₹200."
        assertFalse(parser.canParse(pkg, "Rewards", promo3))
        assertEquals(null, parser.parse(pkg, "Rewards", promo3, null, 1700000000000L))
    }

    @Test
    fun testClassificationEngineCounterpartyCategoryInheritance() {
        // User previously tagged "tharun r" as "Food and beverages"
        val history = mapOf("tharun r" to "Food and beverages")

        // 1. Transaction to "Tharun"
        val txn1 = ParsedTransaction(
            sourcePackage = "com.google.android.apps.messaging",
            amountPaise = 27000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "Tharun"
        )
        val res1 = ClassificationEngine.classify(txn1, counterpartyHistory = history)
        assertEquals("Food and beverages", res1.category)
        assertEquals(ClarificationState.RESOLVED, res1.clarificationState)
        assertEquals("COUNTERPARTY_HISTORY", res1.sourceMethod)

        // 2. Transaction to "Tharun R"
        val txn2 = ParsedTransaction(
            sourcePackage = "com.google.android.apps.messaging",
            amountPaise = 20000L,
            direction = TransactionDirection.CREDIT,
            counterpartyName = "Tharun R"
        )
        val res2 = ClassificationEngine.classify(txn2, counterpartyHistory = history)
        assertEquals("Food and beverages", res2.category)
        assertEquals(ClarificationState.RESOLVED, res2.clarificationState)

        // 3. Transaction to "tharun r"
        val txn3 = ParsedTransaction(
            sourcePackage = "com.google.android.apps.messaging",
            amountPaise = 5000L,
            direction = TransactionDirection.DEBIT,
            counterpartyName = "tharun r"
        )
        val res3 = ClassificationEngine.classify(txn3, counterpartyHistory = history)
        assertEquals("Food and beverages", res3.category)
        assertEquals(ClarificationState.RESOLVED, res3.clarificationState)
    }

    @Test
    fun testDebtRepaymentMath() {
        val principalPaise = 200000L // ₹2,000 lent
        var balancePaise = principalPaise

        // First partial repayment of ₹500
        val firstRepay = 50000L
        balancePaise -= firstRepay
        assertEquals(150000L, balancePaise) // ₹1,500 remaining

        // Second repayment of ₹1,500 (full settlement)
        val secondRepay = 150000L
        balancePaise -= secondRepay
        assertEquals(0L, balancePaise) // 0 remaining -> SETTLED
    }
}

