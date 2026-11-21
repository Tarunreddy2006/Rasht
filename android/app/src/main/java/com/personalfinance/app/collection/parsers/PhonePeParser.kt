package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import java.util.regex.Pattern

class PhonePeParser : NotificationParser {
    override val supportedPackages = setOf("com.phonepe.app")
    override val displayName = "PhonePe"

    companion object {
        fun isPromotional(text: String): Boolean {
            val lower = text.lowercase()
            val promoKeywords = listOf(
                "offer", "worth", "win up to", "win ₹", "win rs", "winner",
                "cashback", "reward", "scratch card", "coupon", "voucher",
                "discount", "save up to", "congratulations", "flat ₹", "flat rs",
                "upto ₹", "upto rs", "up to ₹", "up to rs", "bonus", "free",
                "gift card", "claim", "spin", "unlocked", "recharge now",
                "credit score", "pre-approved", "loan", "insurance", "mutual fund",
                "invest in", "gold", "apply now", "exclusive"
            )
            if (promoKeywords.any { lower.contains(it) }) {
                // Only treat as genuine if it contains unambiguous payment confirmation without promotional words
                val isGenuineDebit = (lower.contains("paid ₹") || lower.contains("paid rs") || lower.contains("payment of ₹") || lower.contains("money sent to")) &&
                        !lower.contains("worth") && !lower.contains("offer") && !lower.contains("win")
                val isGenuineCredit = (lower.contains("received ₹") || lower.contains("received rs") || lower.contains("credited to your account")) &&
                        !lower.contains("worth") && !lower.contains("offer") && !lower.contains("win") && !lower.contains("cashback") && !lower.contains("scratch")
                return !(isGenuineDebit || isGenuineCredit)
            }
            return false
        }
    }

    private val paidPattern1 = Pattern.compile(
        """(?:Paid|Payment of)\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+?)(?:\s+was\s+successful|\s+successful|\.|\$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val moneySentPattern = Pattern.compile(
        """Money sent to\s+(.+?):\s*(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val receivedPattern1 = Pattern.compile(
        """Received\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+from\s+(.+?)(?:\.|\s+Txn|\s+UTR|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val creditedPattern = Pattern.compile(
        """(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+credited\s+to\s+your\s+account\s+from\s+(.+?)(?:\.|\s+Txn|\s+UTR|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val utrPattern = Pattern.compile(
        """(?:UTR|Txn ID|Transaction ID)[:\s]+([A-Za-z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        if (!supportedPackages.contains(packageName)) return false
        val combined = "$title $text"
        if (isPromotional(combined)) return false
        return combined.contains("Paid", ignoreCase = true) ||
               combined.contains("Received", ignoreCase = true) ||
               combined.contains("Money sent", ignoreCase = true) ||
               combined.contains("credited", ignoreCase = true) ||
               combined.contains("Payment", ignoreCase = true)
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
        if (isPromotional(fullText)) return null

        var direction = TransactionDirection.UNKNOWN
        var counterparty: String? = null
        var amountPaise: Long? = null

        val status = when {
            fullText.contains("failed", ignoreCase = true) || fullText.contains("declined", ignoreCase = true) -> TransactionStatus.FAILED
            fullText.contains("pending", ignoreCase = true) -> TransactionStatus.PENDING
            fullText.contains("refund", ignoreCase = true) || fullText.contains("reversed", ignoreCase = true) -> TransactionStatus.REVERSED
            else -> TransactionStatus.SUCCESS
        }

        // Test 1: Paid ₹X to Y
        val p1 = paidPattern1.matcher(fullText)
        if (p1.find()) {
            amountPaise = AmountHelper.parseToPaise(p1.group(1) ?: "")
            counterparty = p1.group(2)?.trim()
            direction = TransactionDirection.DEBIT
        }

        // Test 2: Money sent to Y: ₹X
        if (amountPaise == null) {
            val p2 = moneySentPattern.matcher(fullText)
            if (p2.find()) {
                counterparty = p2.group(1)?.trim()
                amountPaise = AmountHelper.parseToPaise(p2.group(2) ?: "")
                direction = TransactionDirection.DEBIT
            }
        }

        // Test 3: Received ₹X from Y
        if (amountPaise == null) {
            val p3 = receivedPattern1.matcher(fullText)
            if (p3.find()) {
                amountPaise = AmountHelper.parseToPaise(p3.group(1) ?: "")
                counterparty = p3.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        // Test 4: ₹X credited to your account from Y
        if (amountPaise == null) {
            val p4 = creditedPattern.matcher(fullText)
            if (p4.find()) {
                amountPaise = AmountHelper.parseToPaise(p4.group(1) ?: "")
                counterparty = p4.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        // Fallback amount extraction - STRICT: Never parse promotional/unverified credits
        if (amountPaise == null) {
            val extracted = AmountHelper.extractAmountPaise(fullText) ?: return null
            val dir = when {
                fullText.contains("Paid", ignoreCase = true) || fullText.contains("sent", ignoreCase = true) -> TransactionDirection.DEBIT
                fullText.contains("Received", ignoreCase = true) || fullText.contains("credited", ignoreCase = true) -> TransactionDirection.CREDIT
                else -> TransactionDirection.UNKNOWN
            }
            // If credit without verified counterparty, reject to prevent false offer/cashback capture
            if (dir == TransactionDirection.CREDIT && counterparty.isNullOrBlank()) {
                return null
            }
            amountPaise = extracted
            direction = dir
        }

        var referenceId: String? = null
        val utrMatcher = utrPattern.matcher(fullText)
        if (utrMatcher.find()) {
            referenceId = utrMatcher.group(1)
        }

        if (counterparty != null) {
            counterparty = counterparty.replace(Regex("""(?:was successful|successful|\.)$""", RegexOption.IGNORE_CASE), "").trim()
        }

        // Final safeguard against promotional titles or blank counterparties claiming credit
        if (direction == TransactionDirection.CREDIT && (counterparty.isNullOrBlank() || isPromotional(counterparty.orEmpty()))) {
            return null
        }

        return ParsedTransaction(
            sourcePackage = packageName,
            amountPaise = amountPaise,
            direction = direction,
            status = status,
            counterpartyName = counterparty,
            referenceId = referenceId,
            confidenceScore = if (direction != TransactionDirection.UNKNOWN && !counterparty.isNullOrBlank()) 0.95 else 0.70,
            clarificationState = if (direction == TransactionDirection.UNKNOWN || counterparty.isNullOrBlank()) ClarificationState.PENDING else ClarificationState.RESOLVED,
            timestamp = timestamp,
            rawTitle = title,
            rawText = text
        )
    }
}

