package com.personalfinance.app.database

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.util.UUID

class AppDatabaseHelper(private val context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    SecurityManager.getDatabasePassphrase(context),
    null,
    DATABASE_VERSION,
    0,
    null,
    null,
    false
) {

    companion object {
        const val DATABASE_NAME = "rasht_financial_ledger.db"
        const val DATABASE_VERSION = 4

        // Table names
        const val TABLE_ACCOUNTS = "accounts"
        const val TABLE_SOURCES = "sources"
        const val TABLE_RAW_EVENTS = "raw_events"
        const val TABLE_TRANSACTIONS = "transactions"
        const val TABLE_TRANSACTION_DETAILS = "transaction_details"
        const val TABLE_CATEGORIES = "categories"
        const val TABLE_CONTACTS = "contacts"
        const val TABLE_DEBTS = "debts"
        const val TABLE_DEBT_PAYMENTS = "debt_payments"
        const val TABLE_LEDGER_ENTRIES = "ledger_entries"
        const val TABLE_CLARIFICATIONS = "clarifications"
        const val TABLE_CLASSIFICATION_RULES = "classification_rules"
        const val TABLE_BUDGETS = "budgets"
        const val TABLE_FINANCIAL_NOTES = "financial_notes"
        const val TABLE_REPORTS = "reports"
    }

    fun getWritableEncryptedDb(): SQLiteDatabase {
        SecurityManager.initialize(context)
        return writableDatabase
    }

    fun getReadableEncryptedDb(): SQLiteDatabase {
        SecurityManager.initialize(context)
        return readableDatabase
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Accounts
        db.execSQL("""
            CREATE TABLE $TABLE_ACCOUNTS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('CASH', 'BANK', 'UPI', 'RECEIVABLE', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY')),
                currency TEXT NOT NULL DEFAULT 'INR',
                balance_paise INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 2. Sources
        db.execSQL("""
            CREATE TABLE $TABLE_SOURCES (
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                source_type TEXT NOT NULL CHECK (source_type IN ('UPI_APP', 'BANK_SMS', 'STATEMENT_IMPORT')),
                is_active INTEGER NOT NULL DEFAULT 1
            );
        """.trimIndent())

        // 3. Raw Events (In-memory capture log, purged of sensitive text)
        db.execSQL("""
            CREATE TABLE $TABLE_RAW_EVENTS (
                id TEXT PRIMARY KEY,
                source_package TEXT NOT NULL,
                parse_status TEXT NOT NULL,
                amount_paise INTEGER,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 4. Categories
        db.execSQL("""
            CREATE TABLE $TABLE_CATEGORIES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                icon TEXT,
                parent_id TEXT REFERENCES $TABLE_CATEGORIES(id),
                type TEXT NOT NULL CHECK (type IN ('EXPENSE', 'INCOME', 'TRANSFER'))
            );
        """.trimIndent())

        // 5. Transactions
        db.execSQL("""
            CREATE TABLE $TABLE_TRANSACTIONS (
                id TEXT PRIMARY KEY,
                source_package TEXT NOT NULL,
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT', 'TRANSFER', 'UNKNOWN')),
                status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'PENDING', 'FAILED', 'REVERSED')),
                counterparty_name TEXT,
                counterparty_identifier TEXT,
                reference_id TEXT,
                category_id TEXT,
                confidence_score REAL NOT NULL DEFAULT 0.0,
                clarification_state TEXT NOT NULL DEFAULT 'RESOLVED' CHECK (clarification_state IN ('RESOLVED', 'PENDING', 'IGNORED')),
                timestamp INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 6. Transaction Details
        db.execSQL("""
            CREATE TABLE $TABLE_TRANSACTION_DETAILS (
                id TEXT PRIMARY KEY,
                transaction_id TEXT NOT NULL UNIQUE REFERENCES $TABLE_TRANSACTIONS(id) ON DELETE CASCADE,
                notes TEXT,
                tags TEXT,
                metadata_json TEXT
            );
        """.trimIndent())

        // 7. Contacts
        db.execSQL("""
            CREATE TABLE $TABLE_CONTACTS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                upi_vpa TEXT,
                phone TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 8. Debts (Lending & Borrowing records)
        db.execSQL("""
            CREATE TABLE $TABLE_DEBTS (
                id TEXT PRIMARY KEY,
                contact_id TEXT REFERENCES $TABLE_CONTACTS(id),
                contact_name TEXT NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('LENT', 'BORROWED')),
                principal_paise INTEGER NOT NULL,
                balance_paise INTEGER NOT NULL,
                interest_rate REAL DEFAULT 0.0,
                due_date INTEGER,
                status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SETTLED', 'DEFAULTED')),
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 9. Debt Payments
        db.execSQL("""
            CREATE TABLE $TABLE_DEBT_PAYMENTS (
                id TEXT PRIMARY KEY,
                debt_id TEXT NOT NULL REFERENCES $TABLE_DEBTS(id) ON DELETE CASCADE,
                transaction_id TEXT REFERENCES $TABLE_TRANSACTIONS(id),
                amount_paise INTEGER NOT NULL,
                payment_date INTEGER NOT NULL,
                notes TEXT
            );
        """.trimIndent())

        // 10. Double-entry Ledger Entries
        db.execSQL("""
            CREATE TABLE $TABLE_LEDGER_ENTRIES (
                id TEXT PRIMARY KEY,
                transaction_id TEXT NOT NULL REFERENCES $TABLE_TRANSACTIONS(id) ON DELETE CASCADE,
                account_id TEXT NOT NULL REFERENCES $TABLE_ACCOUNTS(id),
                entry_type TEXT NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
                amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
                sequence INTEGER NOT NULL,
                posted_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 11. Clarifications
        db.execSQL("""
            CREATE TABLE $TABLE_CLARIFICATIONS (
                id TEXT PRIMARY KEY,
                transaction_id TEXT NOT NULL UNIQUE REFERENCES $TABLE_TRANSACTIONS(id) ON DELETE CASCADE,
                question TEXT NOT NULL,
                suggested_options_json TEXT NOT NULL,
                selected_answer TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'IGNORED')),
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 12. Classification Rules
        db.execSQL("""
            CREATE TABLE $TABLE_CLASSIFICATION_RULES (
                id TEXT PRIMARY KEY,
                match_type TEXT NOT NULL CHECK (match_type IN ('EXACT_MERCHANT', 'REGEX_PATTERN', 'COUNTERPARTY', 'KEYWORD')),
                pattern TEXT NOT NULL,
                category_id TEXT NOT NULL REFERENCES $TABLE_CATEGORIES(id),
                priority INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 13. Budgets
        db.execSQL("""
            CREATE TABLE $TABLE_BUDGETS (
                id TEXT PRIMARY KEY,
                category_id TEXT NOT NULL REFERENCES $TABLE_CATEGORIES(id),
                amount_paise INTEGER NOT NULL,
                period TEXT NOT NULL CHECK (period IN ('WEEKLY', 'MONTHLY')),
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 14. Financial Notes
        db.execSQL("""
            CREATE TABLE $TABLE_FINANCIAL_NOTES (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                related_transaction_id TEXT REFERENCES $TABLE_TRANSACTIONS(id),
                created_at INTEGER NOT NULL
            );
        """.trimIndent())

        // 15. Reports
        db.execSQL("""
            CREATE TABLE $TABLE_REPORTS (
                id TEXT PRIMARY KEY,
                period_start INTEGER NOT NULL,
                period_end INTEGER NOT NULL,
                report_type TEXT NOT NULL DEFAULT 'WEEKLY',
                summary_json TEXT NOT NULL,
                generated_at INTEGER NOT NULL
            );
        """.trimIndent())

        // Performance & Deduplication Indexes
        db.execSQL("CREATE INDEX idx_trans_dedup ON $TABLE_TRANSACTIONS (amount_paise, timestamp, direction);")
        db.execSQL("CREATE INDEX idx_trans_ref ON $TABLE_TRANSACTIONS (reference_id);")
        db.execSQL("CREATE INDEX idx_ledger_tx ON $TABLE_LEDGER_ENTRIES (transaction_id);")
        db.execSQL("CREATE INDEX idx_debts_contact ON $TABLE_DEBTS (contact_id);")

        // Seed initial core accounts and initial categories
        seedInitialData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS raw_events;")
            db.execSQL("DROP TABLE IF EXISTS transaction_details;")
            db.execSQL("DROP TABLE IF EXISTS ledger_entries;")
            db.execSQL("DROP TABLE IF EXISTS debt_payments;")
            db.execSQL("DROP TABLE IF EXISTS debts;")
            db.execSQL("DROP TABLE IF EXISTS clarifications;")
            db.execSQL("DROP TABLE IF EXISTS transactions;")
            db.execSQL("DROP TABLE IF EXISTS budgets;")
            db.execSQL("DROP TABLE IF EXISTS financial_notes;")
            db.execSQL("DROP TABLE IF EXISTS reports;")
            db.execSQL("DROP TABLE IF EXISTS classification_rules;")
            db.execSQL("DROP TABLE IF EXISTS categories;")
            db.execSQL("DROP TABLE IF EXISTS sources;")
            db.execSQL("DROP TABLE IF EXISTS accounts;")
            db.execSQL("DROP TABLE IF EXISTS contacts;")
            onCreate(db)
        } else if (oldVersion < 4) {
            // Upgrade from version 3 to 4: purge false promotional entries and recompute real balances
            try {
                db.execSQL("""
                    DELETE FROM $TABLE_LEDGER_ENTRIES WHERE transaction_id IN (
                        SELECT id FROM $TABLE_TRANSACTIONS 
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
                    );
                """.trimIndent())
                db.execSQL("""
                    DELETE FROM $TABLE_CLARIFICATIONS WHERE transaction_id IN (
                        SELECT id FROM $TABLE_TRANSACTIONS 
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
                    );
                """.trimIndent())
                db.execSQL("""
                    DELETE FROM $TABLE_TRANSACTIONS 
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
                    );
                """.trimIndent())
                db.execSQL("""
                    UPDATE $TABLE_ACCOUNTS SET balance_paise = (
                        SELECT COALESCE(SUM(
                            CASE 
                                WHEN direction = 'CREDIT' THEN amount_paise 
                                WHEN direction = 'DEBIT' THEN -amount_paise 
                                ELSE 0 
                            END
                        ), 0) FROM $TABLE_TRANSACTIONS
                    ) WHERE id = 'acc-bank-primary';
                """.trimIndent())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun seedInitialData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()

        // Core Accounts
        val defaultAccounts = listOf(
            Triple("acc-bank-primary", "Primary Bank Account", "BANK"),
            Triple("acc-cash-wallet", "Cash on Hand", "CASH"),
            Triple("acc-upi-linked", "UPI Payment Handle", "UPI")
        )
        for ((id, name, type) in defaultAccounts) {
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_ACCOUNTS (id, name, type, currency, balance_paise, created_at) VALUES (?, ?, ?, 'INR', 0, ?);",
                arrayOf(id, name, type, now)
            )
        }

        // Standard Categories from Specification
        val defaultCategories = listOf(
            "Food and beverages", "Coffee and restaurants", "Groceries", "Transportation",
            "Shopping", "Education", "Entertainment", "Subscriptions", "Utilities",
            "Medical expenses", "Rent", "Personal transfers", "Lending", "Borrowing",
            "Repayments", "Gifts", "Investments", "Other"
        )
        for (cat in defaultCategories) {
            val type = when (cat) {
                "Lending", "Borrowing", "Repayments", "Personal transfers" -> "TRANSFER"
                else -> "EXPENSE"
            }
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_CATEGORIES (id, name, type) VALUES (?, ?, ?);",
                arrayOf("cat-${cat.lowercase().replace(" ", "-")}", cat, type)
            )
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_CATEGORIES (id, name, type) VALUES (?, ?, ?);",
                arrayOf(cat, cat, type)
            )
        }

        // Supported Payment App Sources
        val sources = listOf(
            Triple("com.google.android.apps.nbu.paisa.user", "Google Pay", "UPI_APP"),
            Triple("com.phonepe.app", "PhonePe", "UPI_APP"),
            Triple("net.one97.paytm", "Paytm", "UPI_APP"),
            Triple("in.org.npci.upiapp", "BHIM UPI", "UPI_APP"),
            Triple("com.google.android.apps.messaging", "Bank SMS Monitor", "BANK_SMS")
        )
        for ((pkg, name, type) in sources) {
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_SOURCES (id, package_name, display_name, source_type, is_active) VALUES (?, ?, ?, ?, 1);",
                arrayOf("src-${pkg.hashCode()}", pkg, name, type)
            )
        }
    }
}
