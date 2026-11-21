package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import java.util.regex.Pattern

class BhimParser : NotificationParser {
    override val supportedPackages = setOf("in.org.npci.upiapp")
    override val displayName = "BHIM UPI"

    private val paymentToPattern = Pattern.compile(
        """Payment of\s+(?:Rs\.?|INR|₹)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+?)(?:\s+successful|\.|\$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val receivedFromPattern = Pattern.compile(
        """(?:You have received|Received)\s+(?:Rs\.?|INR|₹)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+from\s+(.+?)(?:\.|\s+UPI|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val refPattern = Pattern.compile(
        """(?:Ref(?:erence)?|UPI Ref(?:erence)?|Txn ID)[:\s]+([A-Za-z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        if (!supportedPackages.contains(packageName)) return false
        val combined = "$title $text"
        return combined.contains("Payment", ignoreCase = true) ||
               combined.contains("received", ignoreCase = true) ||
               combined.contains("transfer", ignoreCase = true)
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
            fullText.contains("reversed", ignoreCase = true) -> TransactionStatus.REVERSED
            else -> TransactionStatus.SUCCESS
        }

        val p1 = paymentToPattern.matcher(fullText)
        if (p1.find()) {
            amountPaise = AmountHelper.parseToPaise(p1.group(1) ?: "")
            counterparty = p1.group(2)?.trim()
            direction = TransactionDirection.DEBIT
        }

        if (amountPaise == null) {
            val p2 = receivedFromPattern.matcher(fullText)
            if (p2.find()) {
                amountPaise = AmountHelper.parseToPaise(p2.group(1) ?: "")
                counterparty = p2.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        if (amountPaise == null) {
            amountPaise = AmountHelper.extractAmountPaise(fullText) ?: return null
            direction = when {
                fullText.contains("to", ignoreCase = true) && fullText.contains("Payment", ignoreCase = true) -> TransactionDirection.DEBIT
                fullText.contains("received", ignoreCase = true) -> TransactionDirection.CREDIT
                else -> TransactionDirection.UNKNOWN
            }
        }

        var referenceId: String? = null
        val refMatcher = refPattern.matcher(fullText)
        if (refMatcher.find()) {
            referenceId = refMatcher.group(1)
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

