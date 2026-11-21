package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import java.util.regex.Pattern

class PaytmParser : NotificationParser {
    override val supportedPackages = setOf("net.one97.paytm")
    override val displayName = "Paytm"

    private val paidPattern = Pattern.compile(
        """Paid\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val paidSuccessfullyPattern = Pattern.compile(
        """Paid successfully to\s+(.+?)\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val moneySentPattern = Pattern.compile(
        """Money sent:\s*(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val receivedPattern = Pattern.compile(
        """Received\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val paymentReceivedPattern = Pattern.compile(
        """Payment received of\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val refPattern = Pattern.compile(
        """(?:UPI Ref(?:erence)?|Order ID|Txn ID)[:\s]+([A-Za-z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        if (!supportedPackages.contains(packageName)) return false
        val combined = "$title $text"
        return combined.contains("Paid", ignoreCase = true) ||
               combined.contains("Received", ignoreCase = true) ||
               combined.contains("Money sent", ignoreCase = true) ||
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

        var direction = TransactionDirection.UNKNOWN
        var counterparty: String? = null
        var amountPaise: Long? = null

        val status = when {
            fullText.contains("failed", ignoreCase = true) || fullText.contains("declined", ignoreCase = true) -> TransactionStatus.FAILED
            fullText.contains("pending", ignoreCase = true) -> TransactionStatus.PENDING
            fullText.contains("refund", ignoreCase = true) || fullText.contains("reversed", ignoreCase = true) -> TransactionStatus.REVERSED
            else -> TransactionStatus.SUCCESS
        }

        val p1 = paidPattern.matcher(fullText)
        if (p1.find()) {
            amountPaise = AmountHelper.parseToPaise(p1.group(1) ?: "")
            counterparty = p1.group(2)?.trim()
            direction = TransactionDirection.DEBIT
        }

        if (amountPaise == null) {
            val p2 = paidSuccessfullyPattern.matcher(fullText)
            if (p2.find()) {
                counterparty = p2.group(1)?.trim()
                amountPaise = AmountHelper.parseToPaise(p2.group(2) ?: "")
                direction = TransactionDirection.DEBIT
            }
        }

        if (amountPaise == null) {
            val p3 = moneySentPattern.matcher(fullText)
            if (p3.find()) {
                amountPaise = AmountHelper.parseToPaise(p3.group(1) ?: "")
                counterparty = p3.group(2)?.trim()
                direction = TransactionDirection.DEBIT
            }
        }

        if (amountPaise == null) {
            val p4 = receivedPattern.matcher(fullText)
            if (p4.find()) {
                amountPaise = AmountHelper.parseToPaise(p4.group(1) ?: "")
                counterparty = p4.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        if (amountPaise == null) {
            val p5 = paymentReceivedPattern.matcher(fullText)
            if (p5.find()) {
                amountPaise = AmountHelper.parseToPaise(p5.group(1) ?: "")
                counterparty = p5.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        if (amountPaise == null) {
            amountPaise = AmountHelper.extractAmountPaise(fullText) ?: return null
            direction = when {
                fullText.contains("Paid", ignoreCase = true) || fullText.contains("sent", ignoreCase = true) -> TransactionDirection.DEBIT
                fullText.contains("Received", ignoreCase = true) -> TransactionDirection.CREDIT
                else -> TransactionDirection.UNKNOWN
            }
        }

        var referenceId: String? = null
        val refMatcher = refPattern.matcher(fullText)
        if (refMatcher.find()) {
            referenceId = refMatcher.group(1)
        }

        if (counterparty != null) {
            counterparty = counterparty.replace(Regex("""(?:successfully|\.)$""", RegexOption.IGNORE_CASE), "").trim()
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

