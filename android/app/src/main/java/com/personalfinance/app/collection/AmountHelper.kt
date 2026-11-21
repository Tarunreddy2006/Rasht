package com.personalfinance.app.collection

import java.util.regex.Pattern

object AmountHelper {
    // Matches ₹, Rs., Rs, INR followed by an amount with commas and optional decimals
    private val AMOUNT_PATTERN = Pattern.compile(
        """(?:₹|Rs\.?|INR)\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        Pattern.CASE_INSENSITIVE
    )

    fun extractAmountPaise(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val matcher = AMOUNT_PATTERN.matcher(text)
        if (matcher.find()) {
            val amountStr = matcher.group(1) ?: return null
            return parseToPaise(amountStr)
        }
        return null
    }

    fun parseToPaise(rawString: String): Long? {
        try {
            // Strip commas and whitespace
            val cleaned = rawString.replace(",", "").trim()
            if (cleaned.isEmpty()) return null

            val parts = cleaned.split(".")
            val rupees = parts[0].toLongOrNull() ?: return null
            val paise = when {
                parts.size == 1 -> 0L
                parts[1].length == 1 -> (parts[1] + "0").toLongOrNull() ?: 0L
                parts[1].length >= 2 -> parts[1].substring(0, 2).toLongOrNull() ?: 0L
                else -> 0L
            }
            return (rupees * 100L) + paise
        } catch (e: Exception) {
            return null
        }
    }
}

