package com.personalfinance.app.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import com.personalfinance.app.classification.ClassificationEngine
import com.personalfinance.app.collection.FinanceNotificationListener
import com.personalfinance.app.debt.DebtManager
import com.personalfinance.app.debt.DebtRecord
import com.personalfinance.app.intelligence.DuplicateCheckResult
import com.personalfinance.app.intelligence.DuplicateDetector
import com.personalfinance.app.ledger.DoubleEntryLedger
import com.personalfinance.app.model.ClarificationState
import com.personalfinance.app.model.ParsedTransaction
import com.personalfinance.app.model.TransactionDirection
import com.personalfinance.app.model.TransactionStatus
import com.personalfinance.app.reporting.WeeklyReportManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TransactionRepository(private val context: Context) {
    private val dbHelper = AppDatabaseHelper(context.applicationContext)

    @Synchronized
    fun recordTransaction(txn: ParsedTransaction): Pair<Boolean, DuplicateCheckResult> {
        // Step 1: Duplicate Detection
        val candidates = getCandidatesForDedup(txn.amountPaise, txn.timestamp, txn.referenceId)
        val dupResult = DuplicateDetector.checkDuplicate(txn, candidates)

        if (dupResult.isDuplicate) {
            logEvent(
                txn.sourcePackage,
                "DUPLICATE_IGNORED:${dupResult.evidence}",
                txn.amountPaise
            )
            if (!txn.referenceId.isNullOrBlank() && dupResult.matchedTransactionId != null) {
                enrichTransaction(dupResult.matchedTransactionId, txn.referenceId, txn.counterpartyIdentifier)
            }
            return Pair(false, dupResult)
        }

        // Step 2: Automatic Classification
        val userRules = getUserRulesMap()
        val history = getCounterpartyHistoryMap()
        val classification = ClassificationEngine.classify(txn, userRules, history)

        val resolvedCategory = if (!txn.category.isNullOrBlank()) txn.category else classification.category
        val resolvedConfidence = if (txn.category != null) 1.0 else classification.confidence
        val resolvedClarification = if (txn.category != null) ClarificationState.RESOLVED else classification.clarificationState

        // Step 3: Atomic Database Insertion
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("id", txn.id)
                put("source_package", txn.sourcePackage)
                put("amount_paise", txn.amountPaise)
                put("direction", txn.direction.name)
                put("status", txn.status.name)
                put("counterparty_name", txn.counterpartyName)
                put("counterparty_identifier", txn.counterpartyIdentifier)
                put("reference_id", txn.referenceId)
                put("category_id", resolvedCategory)
                put("confidence_score", resolvedConfidence)
                put("clarification_state", resolvedClarification.name)
                put("timestamp", txn.timestamp)
                put("created_at", System.currentTimeMillis())
            }

            val inserted = db.insertWithOnConflict(
                AppDatabaseHelper.TABLE_TRANSACTIONS,
                null,
                values,
                net.zetetic.database.sqlcipher.SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L

            if (inserted) {
                ensureAccountExists(db, "acc-bank-primary", "Primary Bank Account", "BANK")
                // Update primary bank account balance
                val balanceDelta = when (txn.direction) {
                    TransactionDirection.DEBIT -> -txn.amountPaise
                    TransactionDirection.CREDIT -> txn.amountPaise
                    else -> 0L
                }
                if (balanceDelta != 0L) {
                    db.execSQL(
                        "UPDATE ${AppDatabaseHelper.TABLE_ACCOUNTS} SET balance_paise = balance_paise + ? WHERE id = 'acc-bank-primary';",
                        arrayOf(balanceDelta)
                    )
                }

                // Post balanced Double-Entry Ledger entries
                val debitAccount: String
                val creditAccount: String
                if (txn.direction == TransactionDirection.CREDIT) {
                    debitAccount = "acc-bank-primary"
                    creditAccount = "acc-income-${resolvedCategory.lowercase().replace(" ", "-")}"
                    ensureAccountExists(db, creditAccount, resolvedCategory, "INCOME")
                } else {
                    debitAccount = "acc-expense-${resolvedCategory.lowercase().replace(" ", "-")}"
                    creditAccount = "acc-bank-primary"
                    ensureAccountExists(db, debitAccount, resolvedCategory, "EXPENSE")
                }
                DoubleEntryLedger.postEntries(db, txn.id, debitAccount, creditAccount, txn.amountPaise, txn.timestamp)

                // If clarification is required, log a clarification record
                if (resolvedClarification == ClarificationState.PENDING) {
                    val prompt = if (txn.direction == TransactionDirection.CREDIT) {
                        "Why did you receive ₹${txn.formattedRupees} from ${txn.counterpartyName ?: "this person"}?"
                    } else {
                        "Why did you send ₹${txn.formattedRupees} to ${txn.counterpartyName ?: "this counterparty"}?"
                    }
                    val optionsJson = if (txn.direction == TransactionDirection.CREDIT) {
                        """["Someone returned borrowed money","I received borrowed money","Income","Gift","Shared settlement","Transfer from own account","Other"]"""
                    } else {
                        """["Personal expense","I lent money","Repayment of debt","Shared bill","Gift","Transfer to own account","Other"]"""
                    }
                    db.execSQL(
                        "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLARIFICATIONS} (id, transaction_id, question, suggested_options_json, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?);",
                        arrayOf(UUID.randomUUID().toString(), txn.id, prompt, optionsJson, System.currentTimeMillis())
                    )
                } else if (!txn.counterpartyName.isNullOrBlank() && resolvedCategory !in listOf("Other", "UNKNOWN", "Personal transfers", "")) {
                    // Auto-record confirmed category rule for future transactions with this person
                    val pattern = txn.counterpartyName.trim().lowercase()
                    db.execSQL(
                        "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES} (id, match_type, pattern, category_id, priority, created_at) VALUES (?, 'COUNTERPARTY', ?, ?, 100, ?);",
                        arrayOf("rule-${pattern.hashCode()}", pattern, resolvedCategory, System.currentTimeMillis())
                    )
                }

                db.setTransactionSuccessful()
            }

            Pair(inserted, dupResult)
        } catch (e: Exception) {
            e.printStackTrace()
            FinanceNotificationListener.addMemoryLog("DB_ERROR", "INSERT_FAILED", "${e.javaClass.simpleName}: ${e.message}")
            Pair(false, dupResult)
        } finally {
            db.endTransaction()
        }
    }

    // Resolves a clarification and triggers debt or accounting updates
    @Synchronized
    fun resolveClarification(clarificationId: String, answer: String): Boolean {
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            var txnId = ""
            var amountPaise = 0L
            var direction = ""
            var partyName = ""

            val cursor = db.rawQuery(
                """
                SELECT c.transaction_id, t.amount_paise, t.direction, t.counterparty_name 
                FROM ${AppDatabaseHelper.TABLE_CLARIFICATIONS} c 
                JOIN ${AppDatabaseHelper.TABLE_TRANSACTIONS} t ON c.transaction_id = t.id 
                WHERE c.id = ? OR c.transaction_id = ?
                """.trimIndent(),
                arrayOf(clarificationId, clarificationId)
            )
            if (cursor.moveToFirst()) {
                txnId = cursor.getString(0)
                amountPaise = cursor.getLong(1)
                direction = cursor.getString(2)
                partyName = cursor.getString(3) ?: "Contact"
            }
            cursor.close()

            if (txnId.isEmpty()) return false

            // Mark clarification resolved
            db.execSQL(
                "UPDATE ${AppDatabaseHelper.TABLE_CLARIFICATIONS} SET status = 'RESOLVED', selected_answer = ? WHERE transaction_id = ?;",
                arrayOf(answer, txnId)
            )

            // Categorize and act based on response
            val newCategory: String
            when (answer) {
                "I lent money" -> {
                    newCategory = "Lending"
                    DebtManager.createDebt(db, partyName, "LENT", amountPaise, txnId)
                }
                "Someone returned borrowed money" -> {
                    newCategory = "Repayments"
                    // Find active debt for this contact
                    val debtId = findActiveDebt(db, partyName, "LENT")
                    if (debtId != null) {
                        DebtManager.recordRepayment(db, debtId, amountPaise, txnId)
                    }
                }
                "I received borrowed money" -> {
                    newCategory = "Borrowing"
                    DebtManager.createDebt(db, partyName, "BORROWED", amountPaise, txnId)
                }
                "Repayment of debt" -> {
                    newCategory = "Repayments"
                    val debtId = findActiveDebt(db, partyName, "BORROWED")
                    if (debtId != null) {
                        DebtManager.recordRepayment(db, debtId, amountPaise, txnId)
                    }
                }
                "Gift" -> newCategory = "Gifts"
                "Personal expense" -> newCategory = "Other"
                "Income" -> newCategory = "Other"
                "Shared bill", "Shared settlement" -> newCategory = "Personal transfers"
                "Transfer to own account", "Transfer from own account" -> newCategory = "Personal transfers"
                else -> newCategory = "Other"
            }

            // Update transaction category and state
            val cv = ContentValues().apply {
                put("category_id", newCategory)
                put("confidence_score", 1.0)
                put("clarification_state", ClarificationState.RESOLVED.name)
            }
            db.update(AppDatabaseHelper.TABLE_TRANSACTIONS, cv, "id = ?", arrayOf(txnId))

            // Auto-learn rule for this person so future transactions inherit the category
            if (partyName.isNotBlank() && partyName != "Contact" && newCategory !in listOf("Other", "UNKNOWN", "Personal transfers", "")) {
                val pattern = partyName.trim().lowercase()
                db.execSQL(
                    "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES} (id, match_type, pattern, category_id, priority, created_at) VALUES (?, 'COUNTERPARTY', ?, ?, 100, ?);",
                    arrayOf("rule-${pattern.hashCode()}", pattern, newCategory, System.currentTimeMillis())
                )
                val firstWord = pattern.split(" ").firstOrNull()?.trim()
                if (!firstWord.isNullOrBlank() && firstWord.length >= 3 && firstWord != pattern) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES} (id, match_type, pattern, category_id, priority, created_at) VALUES (?, 'COUNTERPARTY', ?, ?, 90, ?);",
                        arrayOf("rule-${firstWord.hashCode()}", firstWord, newCategory, System.currentTimeMillis())
                    )
                }
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            db.endTransaction()
        }
    }

    private fun findActiveDebt(db: SQLiteDatabase, contactName: String, type: String): String? {
        var debtId: String? = null
        val cursor = db.rawQuery(
            "SELECT id FROM ${AppDatabaseHelper.TABLE_DEBTS} WHERE lower(contact_name) = lower(?) AND type = ? AND status = 'ACTIVE' ORDER BY created_at ASC LIMIT 1",
            arrayOf(contactName.trim(), type)
        )
        if (cursor.moveToFirst()) {
            debtId = cursor.getString(0)
        }
        cursor.close()
        return debtId
    }

    @Synchronized
    fun getPendingClarifications(): List<Map<String, Any>> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<Map<String, Any>>()
        val cursor = db.rawQuery(
            """
            SELECT c.id, c.transaction_id, c.question, c.suggested_options_json, t.amount_paise, t.direction, t.counterparty_name, t.timestamp 
            FROM ${AppDatabaseHelper.TABLE_CLARIFICATIONS} c 
            JOIN ${AppDatabaseHelper.TABLE_TRANSACTIONS} t ON c.transaction_id = t.id 
            WHERE c.status = 'PENDING' 
            ORDER BY t.timestamp DESC
            """.trimIndent(),
            null
        )
        if (cursor.moveToFirst()) {
            do {
                val item = mapOf(
                    "id" to cursor.getString(0),
                    "transactionId" to cursor.getString(1),
                    "question" to cursor.getString(2),
                    "options" to cursor.getString(3),
                    "amountPaise" to cursor.getLong(4),
                    "amountRupees" to (cursor.getLong(4) / 100.0),
                    "formattedRupees" to String.format("₹%.2f", cursor.getLong(4) / 100.0),
                    "direction" to cursor.getString(5),
                    "counterparty" to (cursor.getString(6) ?: "Contact"),
                    "timestamp" to cursor.getLong(7)
                )
                list.add(item)
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    // Debt Management
    @Synchronized
    fun getDebts(typeFilter: String? = null): List<DebtRecord> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<DebtRecord>()
        val sql = if (typeFilter != null) {
            "SELECT * FROM ${AppDatabaseHelper.TABLE_DEBTS} WHERE type = ? ORDER BY status ASC, created_at DESC"
        } else {
            "SELECT * FROM ${AppDatabaseHelper.TABLE_DEBTS} ORDER BY status ASC, created_at DESC"
        }
        val args = if (typeFilter != null) arrayOf(typeFilter) else null
        val cursor = db.rawQuery(sql, args)
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    DebtRecord(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        contactName = cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        principalPaise = cursor.getLong(cursor.getColumnIndexOrThrow("principal_paise")),
                        balancePaise = cursor.getLong(cursor.getColumnIndexOrThrow("balance_paise")),
                        interestRate = cursor.getDouble(cursor.getColumnIndexOrThrow("interest_rate")),
                        dueDate = if (cursor.isNull(cursor.getColumnIndexOrThrow("due_date"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("due_date")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    @Synchronized
    fun createManualDebt(contactName: String, type: String, amountPaise: Long, notes: String?): String {
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            val id = DebtManager.createDebt(db, contactName, type, amountPaise, null, notes)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun recordDebtRepayment(debtId: String, repaymentPaise: Long, notes: String?): Boolean {
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            val res = DebtManager.recordRepayment(db, debtId, repaymentPaise, null, notes)
            db.setTransactionSuccessful()
            res
        } finally {
            db.endTransaction()
        }
    }

    // Financial Notes (Logbook)
    @Synchronized
    fun getFinancialNotes(): List<Map<String, Any>> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<Map<String, Any>>()
        val cursor = db.rawQuery("SELECT id, title, content, created_at FROM ${AppDatabaseHelper.TABLE_FINANCIAL_NOTES} ORDER BY created_at DESC", null)
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    mapOf(
                        "id" to cursor.getString(0),
                        "title" to cursor.getString(1),
                        "content" to cursor.getString(2),
                        "createdAt" to cursor.getLong(3)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    @Synchronized
    fun addFinancialNote(title: String, content: String): Boolean {
        val db = dbHelper.getWritableEncryptedDb()
        val cv = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("title", title.trim())
            put("content", content.trim())
            put("created_at", System.currentTimeMillis())
        }
        return db.insert(AppDatabaseHelper.TABLE_FINANCIAL_NOTES, null, cv) != -1L
    }

    @Synchronized
    fun deleteFinancialNote(id: String): Boolean {
        val db = dbHelper.getWritableEncryptedDb()
        return db.delete(AppDatabaseHelper.TABLE_FINANCIAL_NOTES, "id = ?", arrayOf(id)) > 0
    }

    // Weekly Reporting on demand
    @Synchronized
    fun generateWeeklyReport(): String {
        val db = dbHelper.getWritableEncryptedDb()
        val json = WeeklyReportManager.generateWeeklyReport(context, db)
        return json.toString()
    }

    @Synchronized
    fun getLatestWeeklyReport(): String? {
        val db = dbHelper.getReadableEncryptedDb()
        var result: String? = null
        val cursor = db.rawQuery("SELECT summary_json FROM ${AppDatabaseHelper.TABLE_REPORTS} ORDER BY generated_at DESC LIMIT 1", null)
        if (cursor.moveToFirst()) {
            result = cursor.getString(0)
        }
        cursor.close()
        return result
    }

    // Analytics Breakdown
    @Synchronized
    fun getAnalyticsSummary(): Map<String, Any> {
        val db = dbHelper.getReadableEncryptedDb()
        val categoryBreakdown = mutableListOf<Map<String, Any>>()
        val topMerchants = mutableListOf<Map<String, Any>>()

        val catCursor = db.rawQuery(
            "SELECT category_id, SUM(amount_paise) as total FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE direction = 'DEBIT' GROUP BY category_id ORDER BY total DESC",
            null
        )
        if (catCursor.moveToFirst()) {
            do {
                val cat = catCursor.getString(0) ?: "Unclassified"
                val sum = catCursor.getLong(1)
                categoryBreakdown.add(mapOf("category" to cat, "amountPaise" to sum, "amountRupees" to (sum / 100.0)))
            } while (catCursor.moveToNext())
        }
        catCursor.close()

        val merchCursor = db.rawQuery(
            "SELECT counterparty_name, SUM(amount_paise) as total FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE direction = 'DEBIT' AND counterparty_name IS NOT NULL GROUP BY counterparty_name ORDER BY total DESC LIMIT 6",
            null
        )
        if (merchCursor.moveToFirst()) {
            do {
                val party = merchCursor.getString(0)
                val sum = merchCursor.getLong(1)
                topMerchants.add(mapOf("merchant" to party, "amountPaise" to sum, "amountRupees" to (sum / 100.0)))
            } while (merchCursor.moveToNext())
        }
        merchCursor.close()

        return mapOf(
            "categories" to categoryBreakdown,
            "merchants" to topMerchants
        )
    }

    // Export Data (JSON & CSV)
    @Synchronized
    fun exportLedgerJson(): String {
        val db = dbHelper.getReadableEncryptedDb()
        val root = JSONObject()

        val txArray = JSONArray()
        val cursor = db.rawQuery("SELECT * FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} ORDER BY timestamp ASC", null)
        if (cursor.moveToFirst()) {
            val cols = cursor.columnNames
            do {
                val row = JSONObject()
                for (col in cols) {
                    row.put(col, cursor.getString(cursor.getColumnIndexOrThrow(col)))
                }
                txArray.put(row)
            } while (cursor.moveToNext())
        }
        cursor.close()

        val debtArray = JSONArray()
        val debtCur = db.rawQuery("SELECT * FROM ${AppDatabaseHelper.TABLE_DEBTS}", null)
        if (debtCur.moveToFirst()) {
            val cols = debtCur.columnNames
            do {
                val row = JSONObject()
                for (col in cols) {
                    row.put(col, debtCur.getString(debtCur.getColumnIndexOrThrow(col)))
                }
                debtArray.put(row)
            } while (debtCur.moveToNext())
        }
        debtCur.close()

        root.put("version", "3.0")
        root.put("exportTime", System.currentTimeMillis())
        root.put("transactions", txArray)
        root.put("debts", debtArray)
        return root.toString(2)
    }

    // Update Category & Learning Rule
    @Synchronized
    fun updateTransactionCategory(txnId: String, newCategory: String, rememberRule: Boolean): Boolean {
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            var counterparty: String? = null
            var cursor = db.rawQuery("SELECT counterparty_name FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE id = ?", arrayOf(txnId))
            if (cursor.moveToFirst()) {
                counterparty = cursor.getString(0)
            }
            cursor.close()

            val cv = ContentValues().apply {
                put("category_id", newCategory)
                put("confidence_score", 1.0)
                put("clarification_state", ClarificationState.RESOLVED.name)
            }
            db.update(AppDatabaseHelper.TABLE_TRANSACTIONS, cv, "id = ?", arrayOf(txnId))

            db.execSQL("UPDATE ${AppDatabaseHelper.TABLE_CLARIFICATIONS} SET status = 'RESOLVED', selected_answer = ? WHERE transaction_id = ?;", arrayOf(newCategory, txnId))

            if (rememberRule && !counterparty.isNullOrBlank()) {
                val pattern = counterparty.trim().lowercase()
                db.execSQL(
                    "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES} (id, match_type, pattern, category_id, priority, created_at) VALUES (?, 'COUNTERPARTY', ?, ?, 100, ?);",
                    arrayOf("rule-${pattern.hashCode()}", pattern, newCategory, System.currentTimeMillis())
                )
                val firstWord = pattern.split(" ").firstOrNull()?.trim()
                if (!firstWord.isNullOrBlank() && firstWord.length >= 3 && firstWord != pattern) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES} (id, match_type, pattern, category_id, priority, created_at) VALUES (?, 'COUNTERPARTY', ?, ?, 90, ?);",
                        arrayOf("rule-${firstWord.hashCode()}", firstWord, newCategory, System.currentTimeMillis())
                    )
                }

                // Retroactively update other transactions for the same counterparty that were 'Other' or 'Personal transfers'
                db.execSQL(
                    """
                    UPDATE ${AppDatabaseHelper.TABLE_TRANSACTIONS} 
                    SET category_id = ?, confidence_score = 1.0, clarification_state = 'RESOLVED' 
                    WHERE (category_id IN ('Other', 'UNKNOWN', 'Personal transfers') OR category_id IS NULL) 
                      AND lower(trim(counterparty_name)) = lower(?)
                    """.trimIndent(),
                    arrayOf(newCategory, counterparty.trim())
                )
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getUserRulesMap(): Map<String, String> {
        val db = dbHelper.getReadableEncryptedDb()
        val rules = mutableMapOf<String, String>()
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery("SELECT pattern, category_id FROM ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES}", null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    rules[cursor.getString(0)] = cursor.getString(1)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return rules
    }

    @Synchronized
    fun getCounterpartyHistoryMap(): Map<String, String> {
        val db = dbHelper.getReadableEncryptedDb()
        val map = mutableMapOf<String, String>()
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(
                """
                SELECT lower(trim(counterparty_name)), category_id FROM (
                    SELECT counterparty_name, category_id, timestamp 
                    FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} 
                    WHERE counterparty_name IS NOT NULL 
                      AND trim(counterparty_name) != '' 
                      AND category_id IS NOT NULL 
                      AND category_id NOT IN ('Other', 'UNKNOWN', 'Personal transfers', '') 
                    ORDER BY timestamp DESC
                ) GROUP BY lower(trim(counterparty_name))
                """.trimIndent(),
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val party = cursor.getString(0)
                    val cat = cursor.getString(1)
                    if (party.isNotEmpty() && cat.isNotEmpty()) {
                        map[party] = cat
                    }
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return map
    }

    @Synchronized
    fun purgeFalsePromotions(): Int {
        val db = dbHelper.getWritableEncryptedDb()
        db.beginTransaction()
        return try {
            val cursor = db.rawQuery(
                """
                SELECT id FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS}
                WHERE direction = 'CREDIT' AND (
                    (category_id IN ('Other', 'UNKNOWN', '') OR category_id IS NULL) AND (
                        counterparty_name IS NULL 
                        OR trim(counterparty_name) = '' 
                        OR lower(counterparty_name) LIKE '%phonepe%'
                        OR lower(counterparty_name) LIKE '%offer%'
                        OR lower(counterparty_name) LIKE '%reward%'
                        OR lower(counterparty_name) LIKE '%cashback%'
                        OR lower(counterparty_name) LIKE '%scratch%'
                        OR source_package = 'com.phonepe.app'
                    )
                )
                """.trimIndent(),
                null
            )
            val idsToDelete = mutableListOf<String>()
            if (cursor.moveToFirst()) {
                do {
                    idsToDelete.add(cursor.getString(0))
                } while (cursor.moveToNext())
            }
            cursor.close()

            var deletedCount = 0
            for (id in idsToDelete) {
                db.delete(AppDatabaseHelper.TABLE_LEDGER_ENTRIES, "transaction_id = ?", arrayOf(id))
                db.delete(AppDatabaseHelper.TABLE_CLARIFICATIONS, "transaction_id = ?", arrayOf(id))
                db.delete(AppDatabaseHelper.TABLE_TRANSACTION_DETAILS, "transaction_id = ?", arrayOf(id))
                db.delete(AppDatabaseHelper.TABLE_TRANSACTIONS, "id = ?", arrayOf(id))
                deletedCount++
            }

            // Recalculate accurate primary bank account balance from remaining real transactions
            val sumCursor = db.rawQuery(
                """
                SELECT COALESCE(SUM(
                    CASE 
                        WHEN direction = 'CREDIT' THEN amount_paise 
                        WHEN direction = 'DEBIT' THEN -amount_paise 
                        ELSE 0 
                    END
                ), 0) FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS}
                """.trimIndent(),
                null
            )
            var realBankBalance = 0L
            if (sumCursor.moveToFirst()) {
                realBankBalance = sumCursor.getLong(0)
            }
            sumCursor.close()

            db.execSQL(
                "UPDATE ${AppDatabaseHelper.TABLE_ACCOUNTS} SET balance_paise = ? WHERE id = 'acc-bank-primary'",
                arrayOf(realBankBalance)
            )

            db.setTransactionSuccessful()
            deletedCount
        } catch (e: Exception) {
            e.printStackTrace()
            0
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getAllCategories(): List<String> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<String>()
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery("SELECT name FROM ${AppDatabaseHelper.TABLE_CATEGORIES} ORDER BY name ASC", null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursor.getString(0))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    private fun getCandidatesForDedup(amountPaise: Long, timestamp: Long, refId: String?): List<ParsedTransaction> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<ParsedTransaction>()
        var cursor: Cursor? = null

        val minTime = timestamp - DuplicateDetector.DEDUP_WINDOW_MS
        val maxTime = timestamp + DuplicateDetector.DEDUP_WINDOW_MS

        try {
            cursor = if (!refId.isNullOrBlank()) {
                db.rawQuery(
                    "SELECT * FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE reference_id = ? OR (amount_paise = ? AND timestamp BETWEEN ? AND ?)",
                    arrayOf(refId, amountPaise.toString(), minTime.toString(), maxTime.toString())
                )
            } else {
                db.rawQuery(
                    "SELECT * FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE amount_paise = ? AND timestamp BETWEEN ? AND ?",
                    arrayOf(amountPaise.toString(), minTime.toString(), maxTime.toString())
                )
            }

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToTransaction(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    private fun enrichTransaction(id: String, referenceId: String?, accountTail: String?) {
        val db = dbHelper.getWritableEncryptedDb()
        try {
            val cv = ContentValues()
            if (!referenceId.isNullOrBlank()) cv.put("reference_id", referenceId)
            if (!accountTail.isNullOrBlank()) cv.put("counterparty_identifier", accountTail)
            if (cv.size() > 0) {
                db.update(AppDatabaseHelper.TABLE_TRANSACTIONS, cv, "id = ?", arrayOf(id))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureAccountExists(db: SQLiteDatabase, id: String, name: String, type: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO ${AppDatabaseHelper.TABLE_ACCOUNTS} (id, name, type, currency, balance_paise, created_at) VALUES (?, ?, ?, 'INR', 0, ?);",
            arrayOf(id, name, type, System.currentTimeMillis())
        )
    }

    @Synchronized
    fun logEvent(sourcePackage: String, status: String, amountPaise: Long? = null) {
        val db = dbHelper.getWritableEncryptedDb()
        try {
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("source_package", sourcePackage)
                put("parse_status", status)
                put("amount_paise", amountPaise)
                put("created_at", System.currentTimeMillis())
            }
            db.insert(AppDatabaseHelper.TABLE_RAW_EVENTS, null, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getDebugLogs(limit: Int = 50): List<Map<String, Any>> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<Map<String, Any>>()
        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(
                "SELECT id, source_package, parse_status, amount_paise, created_at FROM ${AppDatabaseHelper.TABLE_RAW_EVENTS} ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val map = mutableMapOf<String, Any>()
                    map["id"] = cursor.getString(0)
                    map["sourcePackage"] = cursor.getString(1)
                    map["status"] = cursor.getString(2)
                    if (!cursor.isNull(3)) {
                        map["amountPaise"] = cursor.getLong(3)
                    }
                    map["createdAt"] = cursor.getLong(4)
                    list.add(map)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    @Synchronized
    fun clearDebugLogs() {
        val db = dbHelper.getWritableEncryptedDb()
        try {
            db.delete(AppDatabaseHelper.TABLE_RAW_EVENTS, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getTransactions(limit: Int = 100): List<ParsedTransaction> {
        val db = dbHelper.getReadableEncryptedDb()
        val list = mutableListOf<ParsedTransaction>()
        var cursor: Cursor? = null
        try {
            cursor = db.query(
                AppDatabaseHelper.TABLE_TRANSACTIONS,
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC",
                limit.toString()
            )

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursorToTransaction(cursor))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return list
    }

    private fun cursorToTransaction(cursor: Cursor): ParsedTransaction {
        val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
        val sourcePkg = cursor.getString(cursor.getColumnIndexOrThrow("source_package"))
        val amountPaise = cursor.getLong(cursor.getColumnIndexOrThrow("amount_paise"))
        val dirStr = cursor.getString(cursor.getColumnIndexOrThrow("direction"))
        val statusStr = cursor.getString(cursor.getColumnIndexOrThrow("status"))
        val partyName = cursor.getString(cursor.getColumnIndexOrThrow("counterparty_name"))
        val partyId = cursor.getString(cursor.getColumnIndexOrThrow("counterparty_identifier"))
        val refId = cursor.getString(cursor.getColumnIndexOrThrow("reference_id"))
        val category = cursor.getString(cursor.getColumnIndexOrThrow("category_id"))
        val confidence = cursor.getDouble(cursor.getColumnIndexOrThrow("confidence_score"))
        val clarStateStr = cursor.getString(cursor.getColumnIndexOrThrow("clarification_state"))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))

        val direction = try { TransactionDirection.valueOf(dirStr) } catch (e: Exception) { TransactionDirection.UNKNOWN }
        val status = try { TransactionStatus.valueOf(statusStr) } catch (e: Exception) { TransactionStatus.SUCCESS }
        val clarState = try { ClarificationState.valueOf(clarStateStr) } catch (e: Exception) { ClarificationState.RESOLVED }

        return ParsedTransaction(
            id = id,
            sourcePackage = sourcePkg,
            amountPaise = amountPaise,
            direction = direction,
            status = status,
            counterpartyName = partyName,
            counterpartyIdentifier = partyId,
            referenceId = refId,
            category = category,
            confidenceScore = confidence,
            clarificationState = clarState,
            timestamp = timestamp
        )
    }

    @Synchronized
    fun clearAll() {
        val db = dbHelper.getWritableEncryptedDb()
        try {
            db.delete(AppDatabaseHelper.TABLE_TRANSACTIONS, null, null)
            db.delete(AppDatabaseHelper.TABLE_RAW_EVENTS, null, null)
            db.delete(AppDatabaseHelper.TABLE_CLASSIFICATION_RULES, null, null)
            db.delete(AppDatabaseHelper.TABLE_CLARIFICATIONS, null, null)
            db.delete(AppDatabaseHelper.TABLE_DEBTS, null, null)
            db.delete(AppDatabaseHelper.TABLE_DEBT_PAYMENTS, null, null)
            db.delete(AppDatabaseHelper.TABLE_LEDGER_ENTRIES, null, null)
            db.delete(AppDatabaseHelper.TABLE_FINANCIAL_NOTES, null, null)
            db.delete(AppDatabaseHelper.TABLE_REPORTS, null, null)
            db.execSQL("UPDATE ${AppDatabaseHelper.TABLE_ACCOUNTS} SET balance_paise = 0;")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getStats(): Map<String, Any> {
        val db = dbHelper.getReadableEncryptedDb()
        var totalCount = 0
        var totalDebitPaise = 0L
        var totalCreditPaise = 0L
        var lastCaptured: Long = 0
        var duplicateCount = 0
        var learnedRulesCount = 0
        var outstandingReceivables = 0L
        var outstandingLiabilities = 0L
        var bankBalancePaise = 0L
        var pendingClarifications = 0

        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery("SELECT COUNT(*), MAX(timestamp) FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS}", null)
            if (cursor.moveToFirst()) {
                totalCount = cursor.getInt(0)
                lastCaptured = cursor.getLong(1)
            }
            cursor.close()

            cursor = db.rawQuery("SELECT direction, SUM(amount_paise) FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} GROUP BY direction", null)
            if (cursor.moveToFirst()) {
                do {
                    val dir = cursor.getString(0)
                    val sum = cursor.getLong(1)
                    if (dir == TransactionDirection.DEBIT.name) totalDebitPaise = sum
                    if (dir == TransactionDirection.CREDIT.name) totalCreditPaise = sum
                } while (cursor.moveToNext())
            }
            cursor.close()

            cursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_RAW_EVENTS} WHERE parse_status LIKE 'DUPLICATE_IGNORED%'", null)
            if (cursor.moveToFirst()) duplicateCount = cursor.getInt(0)
            cursor.close()

            cursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_CLASSIFICATION_RULES}", null)
            if (cursor.moveToFirst()) learnedRulesCount = cursor.getInt(0)
            cursor.close()

            cursor = db.rawQuery("SELECT type, SUM(balance_paise) FROM ${AppDatabaseHelper.TABLE_DEBTS} WHERE status = 'ACTIVE' GROUP BY type", null)
            if (cursor.moveToFirst()) {
                do {
                    val type = cursor.getString(0)
                    val sum = cursor.getLong(1)
                    if (type == "LENT") outstandingReceivables = sum
                    if (type == "BORROWED") outstandingLiabilities = sum
                } while (cursor.moveToNext())
            }
            cursor.close()

            cursor = db.rawQuery("SELECT balance_paise FROM ${AppDatabaseHelper.TABLE_ACCOUNTS} WHERE id = 'acc-bank-primary'", null)
            if (cursor.moveToFirst()) bankBalancePaise = cursor.getLong(0)
            cursor.close()

            cursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_CLARIFICATIONS} WHERE status = 'PENDING'", null)
            if (cursor.moveToFirst()) pendingClarifications = cursor.getInt(0)
            cursor.close()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }

        val netWorthPaise = (bankBalancePaise + outstandingReceivables) - outstandingLiabilities

        return mapOf(
            "totalCount" to totalCount,
            "lastCaptured" to lastCaptured,
            "totalDebitPaise" to totalDebitPaise,
            "totalCreditPaise" to totalCreditPaise,
            "duplicateCount" to duplicateCount,
            "learnedRulesCount" to learnedRulesCount,
            "outstandingReceivablesPaise" to outstandingReceivables,
            "outstandingLiabilitiesPaise" to outstandingLiabilities,
            "bankBalancePaise" to bankBalancePaise,
            "netWorthPaise" to netWorthPaise,
            "pendingClarifications" to pendingClarifications,
            "isEncrypted" to true
        )
    }
}
