package com.personalfinance.app.intelligence

import com.personalfinance.app.model.ParsedTransaction

enum class DuplicateEvidence {
    MATCHING_REFERENCE_ID,
    CROSS_CHANNEL_BANK_UPI_MATCH,
    WINDOW_AND_PARTY_MATCH,
    NO_DUPLICATE
}

data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val evidence: DuplicateEvidence,
    val matchedTransactionId: String? = null
)

object DuplicateDetector {
    // 2-minute deduplication window for cross-app notifications (e.g. UPI notification + Bank SMS)
    const val DEDUP_WINDOW_MS = 120_000L

    fun checkDuplicate(
        newTxn: ParsedTransaction,
        existingCandidates: List<ParsedTransaction>
    ): DuplicateCheckResult {
        for (candidate in existingCandidates) {
            // Check 1: Exact Transaction/UTR Reference match
            if (!newTxn.referenceId.isNullOrBlank() && !candidate.referenceId.isNullOrBlank()) {
                if (newTxn.referenceId.equals(candidate.referenceId, ignoreCase = true)) {
                    return DuplicateCheckResult(
                        isDuplicate = true,
                        evidence = DuplicateEvidence.MATCHING_REFERENCE_ID,
                        matchedTransactionId = candidate.id
                    )
                }
            }

            // Check 2: Same amount in paise and same direction
            if (newTxn.amountPaise == candidate.amountPaise && newTxn.direction == candidate.direction) {
                val timeDiff = Math.abs(newTxn.timestamp - candidate.timestamp)
                if (timeDiff <= DEDUP_WINDOW_MS) {
                    val newParty = newTxn.counterpartyName?.trim()?.lowercase() ?: ""
                    val candParty = candidate.counterpartyName?.trim()?.lowercase() ?: ""

                    // Matching counterparty within window
                    if (newParty.isNotEmpty() && candParty.isNotEmpty()) {
                        if (newParty == candParty || newParty.contains(candParty) || candParty.contains(newParty)) {
                            return DuplicateCheckResult(
                                isDuplicate = true,
                                evidence = DuplicateEvidence.WINDOW_AND_PARTY_MATCH,
                                matchedTransactionId = candidate.id
                            )
                        }
                    }

                    // Cross-channel detection: One is UPI app, one is Bank SMS / general notification
                    val isNewSms = newTxn.sourcePackage.contains("messaging") || newTxn.sourcePackage.contains("bank")
                    val isCandSms = candidate.sourcePackage.contains("messaging") || candidate.sourcePackage.contains("bank")
                    if (isNewSms != isCandSms) {
                        return DuplicateCheckResult(
                            isDuplicate = true,
                            evidence = DuplicateEvidence.CROSS_CHANNEL_BANK_UPI_MATCH,
                            matchedTransactionId = candidate.id
                        )
                    }

                    // If within 30 seconds and no conflicting party names, consider duplicate notification echo
                    if (timeDiff <= 30_000L && (newParty.isEmpty() || candParty.isEmpty())) {
                        return DuplicateCheckResult(
                            isDuplicate = true,
                            evidence = DuplicateEvidence.WINDOW_AND_PARTY_MATCH,
                            matchedTransactionId = candidate.id
                        )
                    }
                }
            }
        }

        return DuplicateCheckResult(
            isDuplicate = false,
            evidence = DuplicateEvidence.NO_DUPLICATE
        )
    }
}

