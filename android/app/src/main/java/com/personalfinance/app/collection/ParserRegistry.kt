package com.personalfinance.app.collection

import com.personalfinance.app.collection.parsers.BhimParser
import com.personalfinance.app.collection.parsers.FallbackParser
import com.personalfinance.app.collection.parsers.GenericBankParser
import com.personalfinance.app.collection.parsers.GooglePayParser
import com.personalfinance.app.collection.parsers.PaytmParser
import com.personalfinance.app.collection.parsers.PhonePeParser
import com.personalfinance.app.model.ParsedTransaction

object ParserRegistry {
    private val parsers: List<NotificationParser> = listOf(
        GooglePayParser(),
        PhonePeParser(),
        PaytmParser(),
        BhimParser(),
        GenericBankParser()
    )

    private val fallback = FallbackParser()

    fun isSupportedPackage(packageName: String): Boolean {
        return parsers.any { it.supportedPackages.contains(packageName) }
    }

    fun getAllSupportedPackages(): Set<String> {
        val result = mutableSetOf<String>()
        parsers.forEach { result.addAll(it.supportedPackages) }
        return result
    }

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        timestamp: Long = System.currentTimeMillis()
    ): ParsedTransaction? {
        // 1. First look for parser specifically declared for this package
        for (parser in parsers) {
            if (parser.supportedPackages.contains(packageName) && parser.canParse(packageName, title, text)) {
                val parsed = parser.parse(packageName, title, text, subText, timestamp)
                if (parsed != null) return parsed
            }
        }

        // 2. Next check any parser that says it can parse the content
        for (parser in parsers) {
            if (parser.canParse(packageName, title, text)) {
                val parsed = parser.parse(packageName, title, text, subText, timestamp)
                if (parsed != null) return parsed
            }
        }

        // 3. Fallback heuristic if amount is present
        if (fallback.canParse(packageName, title, text)) {
            return fallback.parse(packageName, title, text, subText, timestamp)
        }

        return null
    }
}

