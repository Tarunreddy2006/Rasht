package com.personalfinance.app.ledger

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.UUID

object DoubleEntryLedger {

    // Ensures balanced double-entry bookkeeping for every transaction
    fun postEntries(
        db: SQLiteDatabase,
        transactionId: String,
        debitAccountId: String,
        creditAccountId: String,
        amountPaise: Long,
        timestamp: Long
    ): Boolean {
        if (amountPaise <= 0) return false

        // Entry 1: Debit entry
        val debitEntry = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("transaction_id", transactionId)
            put("account_id", debitAccountId)
            put("entry_type", "DEBIT")
            put("amount_paise", amountPaise)
            put("sequence", 1)
            put("posted_at", timestamp)
        }

        // Entry 2: Credit entry
        val creditEntry = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("transaction_id", transactionId)
            put("account_id", creditAccountId)
            put("entry_type", "CREDIT")
            put("amount_paise", amountPaise)
            put("sequence", 2)
            put("posted_at", timestamp)
        }

        val dId = db.insert("ledger_entries", null, debitEntry)
        val cId = db.insert("ledger_entries", null, creditEntry)

        return dId != -1L && cId != -1L
    }

    fun verifyLedgerBalance(db: SQLiteDatabase, transactionId: String): Boolean {
        var debitSum = 0L
        var creditSum = 0L

        val cursor = db.rawQuery(
            "SELECT entry_type, SUM(amount_paise) FROM ledger_entries WHERE transaction_id = ? GROUP BY entry_type",
            arrayOf(transactionId)
        )
        if (cursor.moveToFirst()) {
            do {
                val type = cursor.getString(0)
                val sum = cursor.getLong(1)
                if (type == "DEBIT") debitSum = sum
                if (type == "CREDIT") creditSum = sum
            } while (cursor.moveToNext())
        }
        cursor.close()

        return debitSum == creditSum && debitSum > 0
    }
}

