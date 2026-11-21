package com.personalfinance.app.model

import java.util.UUID

data class ParsedTransaction(
    val id: String = UUID.randomUUID().toString(),
    val sourcePackage: String,
    val amountPaise: Long,
    val direction: TransactionDirection,
    val status: TransactionStatus = TransactionStatus.SUCCESS,
    val counterpartyName: String? = null,
    val counterpartyIdentifier: String? = null,
    val referenceId: String? = null,
    val category: String? = null,
    val confidenceScore: Double = 1.0,
    val clarificationState: ClarificationState = ClarificationState.RESOLVED,
    val timestamp: Long = System.currentTimeMillis(),
    val rawTitle: String? = null,
    val rawText: String? = null
) {
    val amountRupees: Double
        get() = amountPaise / 100.0

    val formattedRupees: String
        get() = String.format("₹%.2f", amountPaise / 100.0)
}

