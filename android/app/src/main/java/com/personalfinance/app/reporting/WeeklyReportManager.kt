package com.personalfinance.app.reporting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.personalfinance.app.database.AppDatabaseHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WeeklyReportManager {

    private const val CHANNEL_ID = "rasht_reports_channel"
    private const val NOTIFICATION_ID = 2001

    fun generateWeeklyReport(context: Context, db: SQLiteDatabase): JSONObject {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L)

        var totalIncomePaise = 0L
        var totalExpensePaise = 0L
        var totalTransferredPaise = 0L

        // 1. Transactions totals in last 7 days
        val txCursor = db.rawQuery(
            "SELECT direction, SUM(amount_paise) FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE timestamp >= ? GROUP BY direction",
            arrayOf(sevenDaysAgo.toString())
        )
        if (txCursor.moveToFirst()) {
            do {
                val dir = txCursor.getString(0)
                val sum = txCursor.getLong(1)
                when (dir) {
                    "DEBIT" -> totalExpensePaise = sum
                    "CREDIT" -> totalIncomePaise = sum
                    "TRANSFER" -> totalTransferredPaise = sum
                }
            } while (txCursor.moveToNext())
        }
        txCursor.close()

        // 2. Category-wise expenditure
        val categoryBreakdown = JSONArray()
        val catCursor = db.rawQuery(
            "SELECT category_id, SUM(amount_paise) as total FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE timestamp >= ? AND direction = 'DEBIT' GROUP BY category_id ORDER BY total DESC",
            arrayOf(sevenDaysAgo.toString())
        )
        if (catCursor.moveToFirst()) {
            do {
                val cat = catCursor.getString(0) ?: "Unclassified"
                val sum = catCursor.getLong(1)
                val item = JSONObject().apply {
                    put("category", cat)
                    put("amountPaise", sum)
                    put("amountRupees", sum / 100.0)
                }
                categoryBreakdown.put(item)
            } while (catCursor.moveToNext())
        }
        catCursor.close()

        // 3. Top Merchants
        val merchantBreakdown = JSONArray()
        val merchCursor = db.rawQuery(
            "SELECT counterparty_name, SUM(amount_paise) as total FROM ${AppDatabaseHelper.TABLE_TRANSACTIONS} WHERE timestamp >= ? AND direction = 'DEBIT' AND counterparty_name IS NOT NULL GROUP BY counterparty_name ORDER BY total DESC LIMIT 5",
            arrayOf(sevenDaysAgo.toString())
        )
        if (merchCursor.moveToFirst()) {
            do {
                val party = merchCursor.getString(0)
                val sum = merchCursor.getLong(1)
                val item = JSONObject().apply {
                    put("merchant", party)
                    put("amountPaise", sum)
                    put("amountRupees", sum / 100.0)
                }
                merchantBreakdown.put(item)
            } while (merchCursor.moveToNext())
        }
        merchCursor.close()

        // 4. Receivables & Liabilities
        var outstandingReceivables = 0L
        var outstandingLiabilities = 0L

        val debtCursor = db.rawQuery(
            "SELECT type, SUM(balance_paise) FROM ${AppDatabaseHelper.TABLE_DEBTS} WHERE status = 'ACTIVE' GROUP BY type",
            null
        )
        if (debtCursor.moveToFirst()) {
            do {
                val type = debtCursor.getString(0)
                val sum = debtCursor.getLong(1)
                if (type == "LENT") outstandingReceivables = sum
                if (type == "BORROWED") outstandingLiabilities = sum
            } while (debtCursor.moveToNext())
        }
        debtCursor.close()

        // 5. Bank Balance
        var bankBalancePaise = 0L
        val bankCursor = db.rawQuery("SELECT balance_paise FROM ${AppDatabaseHelper.TABLE_ACCOUNTS} WHERE id = 'acc-bank-primary'", null)
        if (bankCursor.moveToFirst()) {
            bankBalancePaise = bankCursor.getLong(0)
        }
        bankCursor.close()

        // Net Worth = (Bank + Receivables) - Liabilities
        val netWorthPaise = (bankBalancePaise + outstandingReceivables) - outstandingLiabilities

        // 6. Pending Clarifications Count
        var pendingClarifications = 0
        val clarCursor = db.rawQuery("SELECT COUNT(*) FROM ${AppDatabaseHelper.TABLE_CLARIFICATIONS} WHERE status = 'PENDING'", null)
        if (clarCursor.moveToFirst()) {
            pendingClarifications = clarCursor.getInt(0)
        }
        clarCursor.close()

        val reportJson = JSONObject().apply {
            put("periodStart", sevenDaysAgo)
            put("periodEnd", now)
            put("totalIncomePaise", totalIncomePaise)
            put("totalExpensePaise", totalExpensePaise)
            put("totalTransferredPaise", totalTransferredPaise)
            put("outstandingReceivablesPaise", outstandingReceivables)
            put("outstandingLiabilitiesPaise", outstandingLiabilities)
            put("bankBalancePaise", bankBalancePaise)
            put("netWorthPaise", netWorthPaise)
            put("pendingClarifications", pendingClarifications)
            put("categories", categoryBreakdown)
            put("topMerchants", merchantBreakdown)
            put("generatedAt", now)
        }

        // Save report locally to SQLite
        db.execSQL(
            "INSERT INTO ${AppDatabaseHelper.TABLE_REPORTS} (id, period_start, period_end, report_type, summary_json, generated_at) VALUES (?, ?, ?, 'WEEKLY', ?, ?);",
            arrayOf(UUID.randomUUID().toString(), sevenDaysAgo, now, reportJson.toString(), now)
        )

        // Post notification
        postReportNotification(context, totalExpensePaise, totalIncomePaise)

        return reportJson
    }

    private fun postReportNotification(context: Context, expensePaise: Long, incomePaise: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rasht Financial Reports",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Weekly financial ledger summaries"
            }
            nm.createNotificationChannel(channel)
        }

        val expenseRs = String.format("₹%.2f", expensePaise / 100.0)
        val incomeRs = String.format("₹%.2f", incomePaise / 100.0)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Rasht Weekly Financial Digest")
            .setContentText("Total Out: $expenseRs | Total In: $incomeRs")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Weekly Financial Report is ready.\nTotal Outflow: $expenseRs\nTotal Inflow: $incomeRs\nTap to inspect your private ledger breakdown.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}

