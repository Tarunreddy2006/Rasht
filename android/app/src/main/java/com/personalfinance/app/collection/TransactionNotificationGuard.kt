package com.personalfinance.app.collection

/**
 * Conservative gate for financial notifications.
 *
 * A currency amount by itself is not evidence of a transaction. Require an
 * explicit payment/debit/credit phrase, and reject promotional content first.
 */
object TransactionNotificationGuard {
    private const val AMOUNT = """(?:₹|Rs\.?|INR)\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?"""

    private val promotionalPatterns = listOf(
        Regex("""\b(?:offer|offers|cashback|rewards?|scratch\s*cards?|coupons?|vouchers?|discounts?|winners?|bonus|gift\s*cards?|promo(?:tion)?|claim|redeem|unlocked|exclusive)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:win|won|get|earn|save|receive)\s+(?:up\s*to|upto|as\s+much\s+as)?\s*(?:₹|Rs\.?|INR)\s*[0-9]""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:up\s*to|upto|flat)\s*(?:₹|Rs\.?|INR)\s*[0-9]""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:scratch|spin\s+and\s+win|promo\s+code)\b""", RegexOption.IGNORE_CASE)
    )

    private val transactionPatterns = listOf(
        Regex("""\b(?:paid|you paid)\s+${AMOUNT}\s+to\s+\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bpayment\s+of\s+${AMOUNT}\s+to\s+\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bpayment\s+received\s+of\s+${AMOUNT}\s+from\s+\S+""", RegexOption.IGNORE_CASE),
        Regex("""\bpaid\s+successfully\s+to\s+.+?\s+${AMOUNT}\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmoney\s+sent\s+to\s+.+?[:\-]\s*${AMOUNT}\b""", RegexOption.IGNORE_CASE),
        Regex("""\btransferred\s+${AMOUNT}\s+to\s+\S+""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:received|you have received)\s+${AMOUNT}\s+from\s+\S+""", RegexOption.IGNORE_CASE),
        Regex("""${AMOUNT}\s+(?:received|credited)\s+(?:from\s+\S+|to\s+(?:your|the)\s+account)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:debit(?:ed)?|withdrawn|spent|sent|paid)\b.{0,60}${AMOUNT}""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:credit(?:ed)?|deposited|received)\b.{0,60}${AMOUNT}""", RegexOption.IGNORE_CASE),
        Regex("""${AMOUNT}.{0,40}\b(?:debited|credited|debit|credit|deposited)\b""", RegexOption.IGNORE_CASE)
    )

    fun isPromotional(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return promotionalPatterns.any { it.containsMatchIn(text) }
    }

    fun hasExplicitTransactionEvidence(text: String?): Boolean {
        if (text.isNullOrBlank() || isPromotional(text)) return false
        return transactionPatterns.any { it.containsMatchIn(text) }
    }
}
