package com.personalfinance.app.classification

import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection

data class ClassificationResult(
    val category: String,
    val confidence: Double,
    val clarificationState: ClarificationState,
    val sourceMethod: String // "USER_RULE", "MERCHANT_DICT", "COUNTERPARTY_HISTORY", "KEYWORD", "PEER_TRANSFER", "UNKNOWN"
)

object ClassificationEngine {

    const val THRESHOLD_AUTO = 0.90
    const val THRESHOLD_TENTATIVE = 0.65

    fun classify(
        txn: ParsedTransaction,
        userRules: Map<String, String> = emptyMap(),
        counterpartyHistory: Map<String, String> = emptyMap()
    ): ClassificationResult {
        val counterparty = txn.counterpartyName?.trim()
        val normalizedParty = normalizeParty(counterparty)

        // Priority 1: Previously confirmed user rules (Highest Priority, 100% confidence)
        if (normalizedParty.isNotEmpty()) {
            findMatchingRuleOrHistory(counterparty, userRules)?.let { matchedCategory ->
                return ClassificationResult(
                    category = matchedCategory,
                    confidence = 1.0,
                    clarificationState = ClarificationState.RESOLVED,
                    sourceMethod = "USER_RULE"
                )
            }
        }

        // Priority 2: Known merchant dictionary (95% confidence)
        val dictEntry = MerchantDictionary.lookup(counterparty)
        if (dictEntry != null) {
            return ClassificationResult(
                category = dictEntry.category,
                confidence = dictEntry.confidence,
                clarificationState = ClarificationState.RESOLVED,
                sourceMethod = "MERCHANT_DICT"
            )
        }

        // Priority 3: Counterparty transaction history in ledger (98% confidence, automatic inheritance)
        if (normalizedParty.isNotEmpty()) {
            findMatchingRuleOrHistory(counterparty, counterpartyHistory)?.let { historicCategory ->
                return ClassificationResult(
                    category = historicCategory,
                    confidence = 0.98,
                    clarificationState = ClarificationState.RESOLVED,
                    sourceMethod = "COUNTERPARTY_HISTORY"
                )
            }
        }

        // Priority 4: Keyword matching (75% confidence -> Tentative)
        val fullSearchText = "${txn.counterpartyName.orEmpty()} ${txn.rawText.orEmpty()}".trim()
        val keywordMatch = KeywordMatcher.match(fullSearchText)
        if (keywordMatch != null) {
            return ClassificationResult(
                category = keywordMatch.category,
                confidence = keywordMatch.confidence,
                clarificationState = if (keywordMatch.confidence >= THRESHOLD_AUTO) ClarificationState.RESOLVED else ClarificationState.PENDING,
                sourceMethod = "KEYWORD"
            )
        }

        // Priority 5: Personal transfer / Peer-to-Peer detection
        // If money sent to or received from a personal name (e.g., contains 2 capitalized name words without commercial words)
        val isLikelyPerson = isLikelyPersonalName(counterparty)
        if (isLikelyPerson) {
            return ClassificationResult(
                category = "Personal transfers",
                confidence = 0.50, // Below 65% threshold -> Always asks user
                clarificationState = ClarificationState.PENDING,
                sourceMethod = "PEER_TRANSFER"
            )
        }

        // Priority 6: Unknown transaction requiring clarification (<65%)
        return ClassificationResult(
            category = "Other",
            confidence = 0.40,
            clarificationState = ClarificationState.PENDING,
            sourceMethod = "UNKNOWN"
        )
    }

    fun findMatchingRuleOrHistory(candidate: String?, map: Map<String, String>): String? {
        if (candidate.isNullOrBlank() || map.isEmpty()) return null
        val cleanCandidate = normalizeParty(candidate)
        if (cleanCandidate.isBlank()) return null

        // 1. Direct exact map lookup
        map[cleanCandidate]?.let { return it }

        // 2. Exact normalized check across keys
        for ((pattern, category) in map) {
            val cleanPattern = normalizeParty(pattern)
            if (cleanPattern == cleanCandidate) return category
        }

        // 3. Substring / Prefix match if length >= 3 (e.g. "tharun" matching "tharun r")
        for ((pattern, category) in map) {
            val cleanPattern = normalizeParty(pattern)
            if (cleanPattern.length < 3 || cleanCandidate.length < 3) continue
            if (cleanCandidate.startsWith(cleanPattern) || cleanPattern.startsWith(cleanCandidate)) {
                return category
            }
            if (cleanCandidate.contains(cleanPattern) || cleanPattern.contains(cleanCandidate)) {
                return category
            }
        }

        // 4. Token / Word match (e.g. shared significant name tokens)
        val candidateTokens = cleanCandidate.split(" ").filter { it.length >= 3 }
        for ((pattern, category) in map) {
            val patternTokens = normalizeParty(pattern).split(" ").filter { it.length >= 3 }
            if (candidateTokens.any { it in patternTokens }) {
                return category
            }
        }

        return null
    }

    fun normalizeParty(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isLikelyPersonalName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()

        // Exclude common company / commercial suffixes
        val commercialKeywords = listOf(
            "pvt", "ltd", "limited", "store", "shop", "mart", "enterprises", "services",
            "corp", "inc", "co", "trading", "agency", "hospital", "hotel", "restaurant",
            "cafe", "tea", "coffee", "bank", "pay", "upi"
        )
        val lower = trimmed.lowercase()
        if (commercialKeywords.any { lower.contains(it) }) return false

        // Check if string is composed of 1-3 title-case words with alphabet characters only
        val words = trimmed.split(Regex("\\s+"))
        if (words.size in 1..4 && words.all { it.isNotEmpty() && it.first().isUpperCase() && it.all { c -> c.isLetter() || c == '.' } }) {
            return true
        }

        return false
    }
}

