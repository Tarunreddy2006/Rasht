package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import java.util.regex.Pattern

class GenericBankParser : NotificationParser {
    override val supportedPackages = setOf(
        // Direct SMS Receiver & Inbox Scanner sources
        "sms.receiver",
        "sms.inbox",

        // Default & OEM SMS Packages
        "com.google.android.apps.messaging", // Google Messages (Stock, Pixel, Moto, OnePlus, Realme)
        "com.android.mms",                   // Default AOSP / Xiaomi / Realme / Oppo
        "com.coloros.mms",                   // ColorOS (Oppo / Realme)
        "com.heytap.mms",                    // Heytap (Oppo / Realme)
        "com.oneplus.mms",                   // OnePlus Messages
        "com.samsung.android.messaging",     // Samsung Messages
        "com.miui.sms",                      // Xiaomi MIUI SMS
        "com.vivo.mms",                      // Vivo / iQOO SMS
        "com.bbk.mms",                       // BBK Electronics SMS
        "com.truecaller",                    // Truecaller SMS
        "com.microsoft.android.smsorganizer",// Microsoft SMS Organizer

        // Major Banking & Financial Apps
        "com.ippb.mobileapp",                // India Post Payments Bank
        "com.fss.ippb",                      // IPPB
        "com.sbi.lotusintouch",              // YONO SBI
        "com.sbi.SBIFreedomPlus",            // YONO Lite SBI
        "com.snapwork.hdfc",                 // HDFC Bank
        "com.csam.icici.bank.imobile",       // ICICI iMobile
        "com.axis.mobile",                   // Axis Mobile
        "com.msf.kopl",                      // Kotak Mobile
        "com.bankofbaroda.mconnect",         // Bank of Baroda
        "com.pnb.mbanking",                  // PNB ONE
        "com.canarabank.mobility",           // Canara ai1
        "com.unionbank.ecommerce.mobile.android", // Union Bank
        "com.infrasofttech.indianbank",      // IndOASIS (Indian Bank)
        "com.idbicommunity",                 // IDBI Mobile
        "com.fss.idfcanywhere"               // IDFC FIRST Bank
    )
    override val displayName = "Bank & SMS Monitor"

    // Matches "A/c *1234", "Ac Xx0283", "Account 5678", "a/c no. 1234", "Card ending 1234"
    private val accountPattern = Pattern.compile(
        """(?:A/c|Ac|Acct|Account|Card|a/c no\.?)\s*(?:no\.?|ending)?\s*(?:[xX*]+([0-9]{3,6})|([0-9]{3,6}))""",
        Pattern.CASE_INSENSITIVE
    )

    // Format 1: "debited for Rs 270.00", "Debit Rs.200.00", "debited of Rs.270.00", "debit of Rs 200", "withdrawn Rs 500", "sent Rs 200", "spent Rs 150"
    private val debitedPattern1 = Pattern.compile(
        """(?:debited|debit|withdrawn|sent|spent|paid)\s+(?:for|by|with|of|an\s+amount\s+of|towards)?\s*(?:Rs\.?|INR|₹)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    // Format 2: "Rs. 270.00 debited", "Rs 200 debit", "₹500 has been debited", "Rs 200 was debited"
    private val debitedPattern2 = Pattern.compile(
        """(?:Rs\.?|INR|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+(?:has\s+been\s+|is\s+|was\s+|were\s+)?(?:debited|debit|withdrawn|sent|spent|paid)""",
        Pattern.CASE_INSENSITIVE
    )

    // Format 1: "credited for Rs 270.00", "Credit Rs.200.00", "credit of Rs 200", "deposited Rs 500", "received Rs 200"
    private val creditedPattern1 = Pattern.compile(
        """(?:credited|credit|deposited|received)\s+(?:for|by|with|to|of|an\s+amount\s+of|towards)?\s*(?:Rs\.?|INR|₹)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    // Format 2: "Rs. 270.00 credited", "Rs 200 credit", "₹500 has been credited", "Rs 200 was credited"
    private val creditedPattern2 = Pattern.compile(
        """(?:Rs\.?|INR|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+(?:has\s+been\s+|is\s+|was\s+|were\s+)?(?:credited|credit|deposited|received)""",
        Pattern.CASE_INSENSITIVE
    )

    // Beneficiary: "for UPI to tharun r on 10-11-26", "transfer to Tharun Ac Xx0626 dt 09.11.26", "to Swiggy on 08-Nov"
    private val toPartyPattern = Pattern.compile(
        """(?:for\s+UPI\s+to|transfer\s+to|to|towards|info:)\s+([^,;\n]+?)(?:\s+(?:dt|on|ref|dated|UPI|avl|available)|[.;]|$)""",
        Pattern.CASE_INSENSITIVE
    )

    // Remitter: "received from Ramesh", "from Tharun on 08-Nov"
    private val fromPartyPattern = Pattern.compile(
        """(?:received\s+from|from|by)\s+([^,;\n]+?)(?:\s+(?:dt|on|ref|dated|UPI|avl|available)|[.;]|$)""",
        Pattern.CASE_INSENSITIVE
    )

    // Reference ID / UTR: "Ref 631318958144", "UPI:ref 432109876543", "Txn ID 12345", "IMPS Ref no 631407686192"
    private val refPattern = Pattern.compile(
        """(?:UPI:ref|IMPS(?:\s+ref)?(?:\s+no\.?)?|ref(?:\s+no\.?)?|txn(?:\s+id)?|Ref(?:\s+no\.?)?|UTR(?:\s+no\.?)?)[:\s]+([A-Za-z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        val combined = "$title $text"
        val hasTransactionKeyword = combined.contains("debit", ignoreCase = true) ||
                combined.contains("credit", ignoreCase = true) ||
                combined.contains("withdrawn", ignoreCase = true) ||
                combined.contains("deposited", ignoreCase = true) ||
                combined.contains("transfer", ignoreCase = true) ||
                combined.contains("paid", ignoreCase = true) ||
                combined.contains("sent", ignoreCase = true) ||
                combined.contains("spent", ignoreCase = true) ||
                combined.contains("received", ignoreCase = true) ||
                combined.contains("A/c", ignoreCase = true) ||
                combined.contains("Ac ", ignoreCase = true) ||
                combined.contains("Account", ignoreCase = true)

        val hasCurrency = combined.contains("Rs", ignoreCase = true) ||
                combined.contains("INR", ignoreCase = true) ||
                combined.contains("₹")

        return hasTransactionKeyword && hasCurrency
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        timestamp: Long
    ): ParsedTransaction? {
        val fullText = "${title.orEmpty()} ${text.orEmpty()}".trim()
        if (fullText.isEmpty()) return null

        var direction = TransactionDirection.UNKNOWN
        var amountPaise: Long? = null
        var counterparty: String? = null

        // 1. Check Debit (Pattern 1 then Pattern 2)
        val debitMatcher1 = debitedPattern1.matcher(fullText)
        if (debitMatcher1.find()) {
            amountPaise = AmountHelper.parseToPaise(debitMatcher1.group(1) ?: "")
            direction = TransactionDirection.DEBIT
        } else {
            val debitMatcher2 = debitedPattern2.matcher(fullText)
            if (debitMatcher2.find()) {
                amountPaise = AmountHelper.parseToPaise(debitMatcher2.group(1) ?: "")
                direction = TransactionDirection.DEBIT
            }
        }

        if (direction == TransactionDirection.DEBIT) {
            val toMatcher = toPartyPattern.matcher(fullText)
            if (toMatcher.find()) {
                var rawParty = toMatcher.group(1)?.trim()
                // Clean trailing account annotations like "Ac Xx0626", "A/c 1234"
                if (!rawParty.isNullOrBlank()) {
                    rawParty = rawParty.replace(Regex("""(?i)\s+Ac(?:ct|count)?\s+[xX*0-9]+.*"""), "").trim()
                    if (rawParty.isNotBlank()) {
                        counterparty = rawParty
                    }
                }
            }
        }

        // 2. Check Credit (Pattern 1 then Pattern 2)
        if (amountPaise == null) {
            val creditMatcher1 = creditedPattern1.matcher(fullText)
            if (creditMatcher1.find()) {
                amountPaise = AmountHelper.parseToPaise(creditMatcher1.group(1) ?: "")
                direction = TransactionDirection.CREDIT
            } else {
                val creditMatcher2 = creditedPattern2.matcher(fullText)
                if (creditMatcher2.find()) {
                    amountPaise = AmountHelper.parseToPaise(creditMatcher2.group(1) ?: "")
                    direction = TransactionDirection.CREDIT
                }
            }

            if (direction == TransactionDirection.CREDIT) {
                val fromMatcher = fromPartyPattern.matcher(fullText)
                if (fromMatcher.find()) {
                    var rawParty = fromMatcher.group(1)?.trim()
                    if (!rawParty.isNullOrBlank()) {
                        rawParty = rawParty.replace(Regex("""(?i)\s+Ac(?:ct|count)?\s+[xX*0-9]+.*"""), "").trim()
                        if (rawParty.isNotBlank()) {
                            counterparty = rawParty
                        }
                    }
                }
            }
        }

        // 3. Fallback amount extraction if direct pattern didn't capture amount
        if (amountPaise == null) {
            amountPaise = AmountHelper.extractAmountPaise(fullText) ?: return null
            direction = when {
                fullText.contains("debit", ignoreCase = true) || fullText.contains("sent", ignoreCase = true) || fullText.contains("withdrawn", ignoreCase = true) || fullText.contains("spent", ignoreCase = true) || fullText.contains("paid", ignoreCase = true) -> TransactionDirection.DEBIT
                fullText.contains("credit", ignoreCase = true) || fullText.contains("received", ignoreCase = true) || fullText.contains("deposited", ignoreCase = true) -> TransactionDirection.CREDIT
                else -> TransactionDirection.UNKNOWN
            }
        }

        // Extract Account Tail (e.g. "0283")
        var accountTail: String? = null
        val acctMatcher = accountPattern.matcher(fullText)
        if (acctMatcher.find()) {
            accountTail = acctMatcher.group(1) ?: acctMatcher.group(2)
        }

        // Extract Reference / UTR (e.g. "631318958144")
        var referenceId: String? = null
        val refMatcher = refPattern.matcher(fullText)
        if (refMatcher.find()) {
            referenceId = refMatcher.group(1)
        }

        // Clean counterparty
        counterparty = counterparty?.trim('.', ' ', ',', '-')
        if (counterparty.isNullOrBlank()) counterparty = null

        return ParsedTransaction(
            sourcePackage = packageName,
            amountPaise = amountPaise,
            direction = direction,
            status = TransactionStatus.SUCCESS,
            counterpartyName = counterparty,
            counterpartyIdentifier = if (accountTail != null) "A/c *${accountTail}" else null,
            referenceId = referenceId,
            confidenceScore = if (direction != TransactionDirection.UNKNOWN && amountPaise > 0) 0.90 else 0.60,
            clarificationState = if (direction == TransactionDirection.UNKNOWN || counterparty.isNullOrBlank()) ClarificationState.PENDING else ClarificationState.RESOLVED,
            timestamp = timestamp,
            rawTitle = title,
            rawText = text
        )
    }
}

