package com.personalfinance.app.collection

import com.personalfinance.app.collection.parsers.BhimParser
import com.personalfinance.app.collection.parsers.GenericBankParser
import com.personalfinance.app.collection.parsers.GooglePayParser
import com.personalfinance.app.collection.parsers.PaytmParser
import com.personalfinance.app.collection.parsers.PhonePeParser
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection

object ParserRegistry {
    private val parsers: List<NotificationParser> = listOf(
        GooglePayParser(),
        PhonePeParser(),
        PaytmParser(),
        BhimParser(),
        GenericBankParser()
    )

    fun isSupportedPackage(packageName: String): Boolean =
        parsers.any { it.supportedPackages.contains(packageName) }

    fun getAllSupportedPackages(): Set<String> =
        parsers.flatMap { it.supportedPackages }.toSet()

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        timestamp: Long = System.currentTimeMillis()
    ): ParsedTransaction? {
        // Check all visible notification text before parsing. This blocks rupee
        // amounts in marketing, offers, scratch cards and cashback messages.
        val completeText = listOfNotNull(title, text, subText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (!TransactionNotificationGuard.hasExplicitTransactionEvidence(completeText)) {
            return null
        }

        // 1. Prefer parser declared for the source package.
        for (parser in parsers) {
            if (parser.supportedPackages.contains(packageName) &&
                parser.canParse(packageName, title, text)
            ) {
                val parsed = parser.parse(packageName, title, text, subText, timestamp)
                if (isLedgerSafe(parsed)) return parsed
            }
        }

        // 2. Other format-specific parsers may support this sender/source.
        for (parser in parsers) {
            if (parser.canParse(packageName, title, text)) {
                val parsed = parser.parse(packageName, title, text, subText, timestamp)
                if (isLedgerSafe(parsed)) return parsed
            }
        }

        // There is deliberately no "first rupee amount" fallback.
        return null
    }

    private fun isLedgerSafe(transaction: ParsedTransaction?): Boolean =
        transaction != null &&
            transaction.amountPaise > 0L &&
            transaction.direction != TransactionDirection.UNKNOWN
}
