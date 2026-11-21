package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import java.util.regex.Pattern

class GooglePayParser : NotificationParser {
    override val supportedPackages = setOf("com.google.android.apps.nbu.paisa.user")
    override val displayName = "Google Pay"

    private val paidToPattern = Pattern.compile(
        """(?:Paid|You paid)\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+?)(?:\.|\s+using|\s+with|\s+UPI|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val receivedFromPattern = Pattern.compile(
        """(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+received\s+from\s+(.+?)(?:\.|\s+UPI|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val counterpartyPaidYouPattern = Pattern.compile(
        """(.+?)\s+paid you\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    private val transferredPattern = Pattern.compile(
        """Transferred\s+(?:₹|Rs\.?|INR)?\s*([0-9,]+(?:\.[0-9]{1,2})?)\s+to\s+(.+?)(?:\.|\s+UPI|$)""",
        Pattern.CASE_INSENSITIVE
    )

    private val refPattern = Pattern.compile(
        """(?:UPI Ref(?:erence)?|Ref No|Txn ID)[:\s]+([A-Za-z0-9]+)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        if (!supportedPackages.contains(packageName)) return false
        val combined = "$title $text"
        return combined.contains("Paid", ignoreCase = true) ||
               combined.contains("received", ignoreCase = true) ||
               combined.contains("Transferred", ignoreCase = true) ||
               combined.contains("₹") || combined.contains("INR") || combined.contains("Rs")
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

        // Determine Status
        val status = when {
            fullText.contains("failed", ignoreCase = true) || fullText.contains("declined", ignoreCase = true) -> TransactionStatus.FAILED
            fullText.contains("pending", ignoreCase = true) -> TransactionStatus.PENDING
            fullText.contains("reversed", ignoreCase = true) || fullText.contains("refund", ignoreCase = true) -> TransactionStatus.REVERSED
            else -> TransactionStatus.SUCCESS
        }

        // Test 1: Paid to
        val paidMatcher = paidToPattern.matcher(fullText)
        if (paidMatcher.find()) {
            amountPaise = AmountHelper.parseToPaise(paidMatcher.group(1) ?: "")
            counterparty = paidMatcher.group(2)?.trim()
            direction = TransactionDirection.DEBIT
        }

        // Test 2: Received from
        if (amountPaise == null) {
            val recMatcher = receivedFromPattern.matcher(fullText)
            if (recMatcher.find()) {
                amountPaise = AmountHelper.parseToPaise(recMatcher.group(1) ?: "")
                counterparty = recMatcher.group(2)?.trim()
                direction = TransactionDirection.CREDIT
            }
        }

        // Test 3: X paid you Y
        if (amountPaise == null) {
            val paidYouMatcher = counterpartyPaidYouPattern.matcher(fullText)
            if (paidYouMatcher.find()) {
                counterparty = paidYouMatcher.group(1)?.trim()
                amountPaise = AmountHelper.parseToPaise(paidYouMatcher.group(2) ?: "")
                direction = TransactionDirection.CREDIT
            }
        }

        // Test 4: Transferred
        if (amountPaise == null) {
            val transferMatcher = transferredPattern.matcher(fullText)
            if (transferMatcher.find()) {
                amountPaise = AmountHelper.parseToPaise(transferMatcher.group(1) ?: "")
                counterparty = transferMatcher.group(2)?.trim()
                direction = TransactionDirection.TRANSFER
            }
        }

        // Fallback amount extraction if direct regex didn't extract amount
        if (amountPaise == null) {
            amountPaise = AmountHelper.extractAmountPaise(fullText) ?: return null
            direction = when {
                fullText.contains("Paid", ignoreCase = true) || fullText.contains("Sent", ignoreCase = true) -> TransactionDirection.DEBIT
                fullText.contains("Received", ignoreCase = true) || fullText.contains("credited", ignoreCase = true) -> TransactionDirection.CREDIT
                else -> TransactionDirection.UNKNOWN
            }
        }

        // Extract reference ID
        var referenceId: String? = null
        val refMatcher = refPattern.matcher(fullText)
        if (refMatcher.find()) {
            referenceId = refMatcher.group(1)
        }

        // Clean counterparty string
        if (counterparty != null) {
            counterparty = counterparty.replace(Regex("""(?:using UPI|with Google Pay|successfully|\.)$""", RegexOption.IGNORE_CASE), "").trim()
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

