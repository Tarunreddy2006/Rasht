package com.personalfinance.app.model

enum class TransactionDirection {
    DEBIT,
    CREDIT,
    TRANSFER,
    UNKNOWN
}

enum class TransactionStatus {
    SUCCESS,
    PENDING,
    FAILED,
    REVERSED
}

enum class ClarificationState {
    RESOLVED,
    PENDING,
    IGNORED
}

