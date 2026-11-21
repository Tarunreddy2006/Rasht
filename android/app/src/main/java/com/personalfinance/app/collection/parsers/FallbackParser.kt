package com.personalfinance.app.collection.parsers

import com.personalfinance.app.collection.AmountHelper
import com.personalfinance.app.collection.NotificationParser
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus

class FallbackParser : NotificationParser {
    override val supportedPackages = emptySet<String>()
    override val displayName = "Heuristic Fallback"

    override fun canParse(packageName: String, title: String?, text: String?): Boolean {
        val full = "$title $text"
        return AmountHelper.extractAmountPaise(full) != null
    }

    override fun parse(
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        timestamp: Long
    ): ParsedTransaction? {
        val full = "$title $text"
        val amount = AmountHelper.extractAmountPaise(full) ?: return null

        val direction = when {
            full.contains("debited", ignoreCase = true) ||
            full.contains("paid", ignoreCase = true) ||
            full.contains("sent", ignoreCase = true) -> TransactionDirection.DEBIT

            full.contains("credited", ignoreCase = true) ||
            full.contains("received", ignoreCase = true) -> TransactionDirection.CREDIT

            full.contains("transferred", ignoreCase = true) -> TransactionDirection.TRANSFER

            else -> TransactionDirection.UNKNOWN
        }

        val status = when {
            full.contains("failed", ignoreCase = true) -> TransactionStatus.FAILED
            full.contains("pending", ignoreCase = true) -> TransactionStatus.PENDING
            full.contains("refund", ignoreCase = true) -> TransactionStatus.REVERSED
            else -> TransactionStatus.SUCCESS
        }

        return ParsedTransaction(
            sourcePackage = packageName,
            amountPaise = amount,
            direction = direction,
            status = status,
            counterpartyName = "Unknown",
            confidenceScore = 0.50,
            clarificationState = ClarificationState.PENDING,
            timestamp = timestamp,
            rawTitle = title,
            rawText = text
        )
    }
}

