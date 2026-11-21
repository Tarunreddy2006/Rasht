package com.personalfinance.app.debt

import android.content.ContentValues
import com.personalfinance.app.database.AppDatabaseHelper
import com.personalfinance.app.ledger.DoubleEntryLedger
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.UUID

data class DebtRecord(
    val id: String,
    val contactName: String,
    val type: String, // "LENT" (Receivable) or "BORROWED" (Liability)
    val principalPaise: Long,
    val balancePaise: Long,
    val interestRate: Double,
    val dueDate: Long?,
    val status: String, // "ACTIVE", "SETTLED"
    val createdAt: Long
)

object DebtManager {

    fun createDebt(
        db: SQLiteDatabase,
        contactName: String,
        type: String, // LENT or BORROWED
        amountPaise: Long,
        transactionId: String? = null,
        notes: String? = null
    ): String {
        val debtId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Ensure contact exists or create
        val contactId = getOrCreateContact(db, contactName)

        // 2. Insert into debts table
        val values = ContentValues().apply {
            put("id", debtId)
            put("contact_id", contactId)
            put("contact_name", contactName.trim())
            put("type", type)
            put("principal_paise", amountPaise)
            put("balance_paise", amountPaise)
            put("status", "ACTIVE")
            put("created_at", now)
        }
        db.insert(AppDatabaseHelper.TABLE_DEBTS, null, values)

        // 3. Post double-entry ledger entries
        val debitAccount = if (type == "LENT") "acc-receivable-default" else "acc-bank-primary"
        val creditAccount = if (type == "LENT") "acc-bank-primary" else "acc-liability-default"

        ensureAccountExists(db, "acc-receivable-default", "Receivables", "RECEIVABLE")
        ensureAccountExists(db, "acc-liability-default", "Liabilities", "LIABILITY")

        val txId = transactionId ?: "manual-debt-$debtId"
        DoubleEntryLedger.postEntries(db, txId, debitAccount, creditAccount, amountPaise, now)

        return debtId
    }

    fun recordRepayment(
        db: SQLiteDatabase,
        debtId: String,
        repaymentPaise: Long,
        transactionId: String? = null,
        notes: String? = null
    ): Boolean {
        // 1. Fetch debt details
        var currentBalance = 0L
        var debtType = ""
        var contactName = ""

        val cursor = db.rawQuery(
            "SELECT balance_paise, type, contact_name FROM ${AppDatabaseHelper.TABLE_DEBTS} WHERE id = ?",
            arrayOf(debtId)
        )
        if (cursor.moveToFirst()) {
            currentBalance = cursor.getLong(0)
            debtType = cursor.getString(1)
            contactName = cursor.getString(2)
        }
        cursor.close()

        if (currentBalance <= 0) return false

        val newBalance = Math.max(0L, currentBalance - repaymentPaise)
        val newStatus = if (newBalance == 0L) "SETTLED" else "ACTIVE"
        val now = System.currentTimeMillis()

        // 2. Update debt balance
        val cv = ContentValues().apply {
            put("balance_paise", newBalance)
            put("status", newStatus)
        }
        db.update(AppDatabaseHelper.TABLE_DEBTS, cv, "id = ?", arrayOf(debtId))

        // 3. Record debt payment
        val payCv = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("debt_id", debtId)
            put("transaction_id", transactionId)
            put("amount_paise", repaymentPaise)
            put("payment_date", now)
            put("notes", notes)
        }
        db.insert(AppDatabaseHelper.TABLE_DEBT_PAYMENTS, null, payCv)

        // 4. Double-entry ledger adjustment
        val debitAccount = if (debtType == "LENT") "acc-bank-primary" else "acc-liability-default"
        val creditAccount = if (debtType == "LENT") "acc-receivable-default" else "acc-bank-primary"

        ensureAccountExists(db, "acc-receivable-default", "Receivables", "RECEIVABLE")
        ensureAccountExists(db, "acc-liability-default", "Liabilities", "LIABILITY")

        val txId = transactionId ?: "manual-repay-${UUID.randomUUID()}"
        DoubleEntryLedger.postEntries(db, txId, debitAccount, creditAccount, repaymentPaise, now)

        return true
    }

    private fun getOrCreateContact(db: SQLiteDatabase, name: String): String {
        val trimmed = name.trim()
        val cursor = db.rawQuery("SELECT id FROM ${AppDatabaseHelper.TABLE_CONTACTS} WHERE lower(name) = lower(?)", arrayOf(trimmed))
        if (cursor.moveToFirst()) {
            val id = cursor.getString(0)
            cursor.close()
            return id
        }
        cursor.close()

        val newId = UUID.randomUUID().toString()
        val cv = ContentValues().apply {
            put("id", newId)
            put("name", trimmed)
            put("created_at", System.currentTimeMillis())
        }
        db.insert(AppDatabaseHelper.TABLE_CONTACTS, null, cv)
        return newId
    }

    private fun ensureAccountExists(db: SQLiteDatabase, id: String, name: String, type: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO ${AppDatabaseHelper.TABLE_ACCOUNTS} (id, name, type, currency, balance_paise, created_at) VALUES (?, ?, ?, 'INR', 0, ?);",
            arrayOf(id, name, type, System.currentTimeMillis())
        )
    }
}

