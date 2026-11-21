package com.personalfinance.app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.personalfinance.app.collection.SmsInboxScanner
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.personalfinance.app.collection.FinanceNotificationListener
import com.personalfinance.app.collection.ParserRegistry
import com.personalfinance.app.database.TransactionRepository
import com.personalfinance.app.model.ParsedTransaction
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "FinanceBridge")
class FinanceBridgePlugin : Plugin() {

    private lateinit var repository: TransactionRepository

    private val transactionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val txnId = intent?.getStringExtra(FinanceNotificationListener.EXTRA_TRANSACTION_ID)
            val isDup = intent?.getBooleanExtra("is_duplicate", false) ?: false
            val data = JSObject().apply {
                put("transactionId", txnId)
                put("isDuplicate", isDup)
                put("timestamp", System.currentTimeMillis())
            }
            notifyListeners("onTransactionCaptured", data)
        }
    }

    override fun load() {
        super.load()
        repository = TransactionRepository(context)
        val filter = IntentFilter(FinanceNotificationListener.ACTION_NEW_TRANSACTION)
        LocalBroadcastManager.getInstance(context).registerReceiver(transactionReceiver, filter)
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        LocalBroadcastManager.getInstance(context).unregisterReceiver(transactionReceiver)
    }

    @PluginMethod
    fun checkNotificationPermission(call: PluginCall) {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val isGranted = enabledPackages.contains(context.packageName)
        val ret = JSObject().apply {
            put("enabled", isGranted)
            put("serviceConnected", FinanceNotificationListener.isConnected)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun rebindNotificationService(call: PluginCall) {
        FinanceNotificationListener.forceRebind(context)
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val isGranted = enabledPackages.contains(context.packageName)
        val ret = JSObject().apply {
            put("enabled", isGranted)
            put("serviceConnected", FinanceNotificationListener.isConnected)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            call.resolve(JSObject().put("opened", true))
        } catch (e: Exception) {
            call.reject("Could not open notification settings: ${e.message}")
        }
    }

    @PluginMethod
    fun checkBatteryOptimization(call: PluginCall) {
        var isIgnoring = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        } else {
            isIgnoring = true
        }
        val ret = JSObject().apply {
            put("ignoringBatteryOptimizations", isIgnoring)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                call.resolve(JSObject().put("opened", true))
            } else {
                call.resolve(JSObject().put("opened", false))
            }
        } catch (e: Exception) {
            try {
                // Fallback to general battery settings
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                call.resolve(JSObject().put("opened", true))
            } catch (err: Exception) {
                call.reject("Could not open battery settings: ${err.message}")
            }
        }
    }

    @PluginMethod
    fun checkSmsPermission(call: PluginCall) {
        val hasReceive = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val hasRead = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val ret = JSObject().apply {
            put("receiveSms", hasReceive)
            put("readSms", hasRead)
            put("allGranted", hasReceive && hasRead)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun requestSmsPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val act = activity
            if (act != null) {
                act.requestPermissions(
                    arrayOf(
                        android.Manifest.permission.RECEIVE_SMS,
                        android.Manifest.permission.READ_SMS
                    ),
                    101
                )
                call.resolve(JSObject().put("requested", true))
                return
            }
        }
        call.resolve(JSObject().put("requested", false))
    }

    @PluginMethod
    fun syncInboxSms(call: PluginCall) {
        val days = call.getInt("days", 30) ?: 30
        Thread {
            try {
                val result = SmsInboxScanner.scanInbox(context, days)
                val ret = JSObject().apply {
                    put("totalExamined", result.totalSmsExamined)
                    put("financialFound", result.financialSmsFound)
                    put("newSaved", result.newTransactionsSaved)
                    put("duplicates", result.duplicatesIgnored)
                    if (result.errorMessage != null) {
                        put("error", result.errorMessage)
                    }
                }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to scan SMS inbox: ${e.message}")
            }
        }.start()
    }

    @PluginMethod
    fun getDebugLog(call: PluginCall) {
        try {
            val eventsArray = JSArray()

            // 1. In-memory logs (most recent first)
            val memList = FinanceNotificationListener.inMemoryLogs.toList().reversed()
            for (m in memList) {
                val obj = JSObject().apply {
                    put("id", m["id"])
                    put("sourcePackage", m["package"])
                    put("status", m["status"])
                    put("detail", m["detail"])
                    put("createdAt", m["timestamp"])
                    put("source", "MEMORY")
                }
                eventsArray.put(obj)
            }

            // 2. Database events
            val dbLogs = repository.getDebugLogs(50)
            for (dbLog in dbLogs) {
                val obj = JSObject().apply {
                    put("id", dbLog["id"])
                    put("sourcePackage", dbLog["sourcePackage"])
                    put("status", dbLog["status"])
                    put("amountPaise", dbLog["amountPaise"] ?: 0L)
                    put("createdAt", dbLog["createdAt"])
                    put("source", "DATABASE")
                }
                eventsArray.put(obj)
            }

            val ret = JSObject().apply {
                put("serviceConnected", FinanceNotificationListener.isConnected)
                put("totalReceived", FinanceNotificationListener.totalNotificationsReceived)
                put("lastCapturedTime", FinanceNotificationListener.lastCapturedTime)
                put("lastNotificationTime", FinanceNotificationListener.lastNotificationTime)
                put("lastError", FinanceNotificationListener.lastError ?: "")
                put("events", eventsArray)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to retrieve debug log: ${e.message}")
        }
    }

    @PluginMethod
    fun clearDebugLog(call: PluginCall) {
        try {
            FinanceNotificationListener.inMemoryLogs.clear()
            repository.clearDebugLogs()
            call.resolve(JSObject().put("cleared", true))
        } catch (e: Exception) {
            call.reject("Failed to clear debug log: ${e.message}")
        }
    }

    @PluginMethod
    fun getTransactions(call: PluginCall) {
        val limit = call.getInt("limit", 100) ?: 100
        val txns = repository.getTransactions(limit)
        val jsArray = JSArray()

        for (t in txns) {
            val item = JSObject().apply {
                put("id", t.id)
                put("sourcePackage", t.sourcePackage)
                put("amountPaise", t.amountPaise)
                put("amountRupees", t.amountRupees)
                put("formattedRupees", t.formattedRupees)
                put("direction", t.direction.name)
                put("status", t.status.name)
                put("counterpartyName", t.counterpartyName ?: "")
                put("counterpartyIdentifier", t.counterpartyIdentifier ?: "")
                put("referenceId", t.referenceId ?: "")
                put("category", t.category ?: "")
                put("confidenceScore", t.confidenceScore)
                put("clarificationState", t.clarificationState.name)
                put("timestamp", t.timestamp)
            }
            jsArray.put(item)
        }

        call.resolve(JSObject().put("transactions", jsArray))
    }

    @PluginMethod
    fun getDiagnostics(call: PluginCall) {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val isPermissionGranted = enabledPackages.contains(context.packageName)

        val stats = repository.getStats()
        val supportedList = JSArray()
        for (pkg in ParserRegistry.getAllSupportedPackages()) {
            supportedList.put(pkg)
        }

        val ret = JSObject().apply {
            put("permissionGranted", isPermissionGranted)
            put("listenerConnected", FinanceNotificationListener.isConnected)
            put("lastCapturedTime", FinanceNotificationListener.lastCapturedTime)
            put("lastError", FinanceNotificationListener.lastError ?: "")
            put("totalCapturedCount", stats["totalCount"] ?: 0)
            put("totalDebitPaise", stats["totalDebitPaise"] ?: 0L)
            put("totalCreditPaise", stats["totalCreditPaise"] ?: 0L)
            put("duplicateCount", stats["duplicateCount"] ?: 0)
            put("learnedRulesCount", stats["learnedRulesCount"] ?: 0)
            put("outstandingReceivablesPaise", stats["outstandingReceivablesPaise"] ?: 0L)
            put("outstandingLiabilitiesPaise", stats["outstandingLiabilitiesPaise"] ?: 0L)
            put("bankBalancePaise", stats["bankBalancePaise"] ?: 0L)
            put("netWorthPaise", stats["netWorthPaise"] ?: 0L)
            put("pendingClarifications", stats["pendingClarifications"] ?: 0)
            put("isEncrypted", stats["isEncrypted"] ?: true)
            put("storageEngine", "SQLCipher AES-256 (Android Keystore)")
            put("supportedPackages", supportedList)
        }
        call.resolve(ret)
    }

    // Smart Clarification System
    @PluginMethod
    fun getPendingClarifications(call: PluginCall) {
        val list = repository.getPendingClarifications()
        val jsArray = JSArray()
        for (c in list) {
            val obj = JSObject()
            c.forEach { (k, v) -> obj.put(k, v) }
            jsArray.put(obj)
        }
        call.resolve(JSObject().put("clarifications", jsArray))
    }

    @PluginMethod
    fun resolveClarification(call: PluginCall) {
        val clarId = call.getString("clarificationId") ?: return call.reject("clarificationId required")
        val answer = call.getString("answer") ?: return call.reject("answer required")

        val success = repository.resolveClarification(clarId, answer)
        call.resolve(JSObject().put("success", success))
    }

    // Debt Management
    @PluginMethod
    fun getDebts(call: PluginCall) {
        val filter = call.getString("type") // "LENT" or "BORROWED"
        val debts = repository.getDebts(filter)
        val jsArray = JSArray()

        for (d in debts) {
            val item = JSObject().apply {
                put("id", d.id)
                put("contactName", d.contactName)
                put("type", d.type)
                put("principalPaise", d.principalPaise)
                put("balancePaise", d.balancePaise)
                put("formattedPrincipal", String.format("₹%.2f", d.principalPaise / 100.0))
                put("formattedBalance", String.format("₹%.2f", d.balancePaise / 100.0))
                put("interestRate", d.interestRate)
                put("dueDate", d.dueDate ?: 0L)
                put("status", d.status)
                put("createdAt", d.createdAt)
            }
            jsArray.put(item)
        }
        call.resolve(JSObject().put("debts", jsArray))
    }

    @PluginMethod
    fun createManualDebt(call: PluginCall) {
        val contact = call.getString("contactName") ?: return call.reject("contactName required")
        val type = call.getString("type") ?: return call.reject("type required (LENT/BORROWED)")
        val amountPaise = call.getLong("amountPaise") ?: return call.reject("amountPaise required")
        val notes = call.getString("notes")

        val debtId = repository.createManualDebt(contact, type, amountPaise, notes)
        call.resolve(JSObject().put("success", true).put("debtId", debtId))
    }

    @PluginMethod
    fun recordDebtRepayment(call: PluginCall) {
        val debtId = call.getString("debtId") ?: return call.reject("debtId required")
        val amountPaise = call.getLong("amountPaise") ?: return call.reject("amountPaise required")
        val notes = call.getString("notes")

        val success = repository.recordDebtRepayment(debtId, amountPaise, notes)
        call.resolve(JSObject().put("success", success))
    }

    // Financial Logbook Notes
    @PluginMethod
    fun getFinancialNotes(call: PluginCall) {
        val notes = repository.getFinancialNotes()
        val jsArray = JSArray()
        for (n in notes) {
            val obj = JSObject()
            n.forEach { (k, v) -> obj.put(k, v) }
            jsArray.put(obj)
        }
        call.resolve(JSObject().put("notes", jsArray))
    }

    @PluginMethod
    fun addFinancialNote(call: PluginCall) {
        val title = call.getString("title") ?: return call.reject("title required")
        val content = call.getString("content") ?: return call.reject("content required")

        val success = repository.addFinancialNote(title, content)
        call.resolve(JSObject().put("success", success))
    }

    @PluginMethod
    fun deleteFinancialNote(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id required")
        val success = repository.deleteFinancialNote(id)
        call.resolve(JSObject().put("success", success))
    }

    // Reporting & Analytics
    @PluginMethod
    fun generateWeeklyReport(call: PluginCall) {
        val reportJsonStr = repository.generateWeeklyReport()
        call.resolve(JSObject().put("report", JSObject(reportJsonStr)))
    }

    @PluginMethod
    fun getLatestWeeklyReport(call: PluginCall) {
        val reportJsonStr = repository.getLatestWeeklyReport()
        if (reportJsonStr != null) {
            call.resolve(JSObject().put("report", JSObject(reportJsonStr)))
        } else {
            // Generate immediately
            val freshReport = repository.generateWeeklyReport()
            call.resolve(JSObject().put("report", JSObject(freshReport)))
        }
    }

    @PluginMethod
    @Suppress("UNCHECKED_CAST")
    fun getAnalyticsSummary(call: PluginCall) {
        val summary = repository.getAnalyticsSummary()
        val categories = summary["categories"] as? List<Map<String, Any>> ?: emptyList()
        val merchants = summary["merchants"] as? List<Map<String, Any>> ?: emptyList()

        val catArray = JSArray()
        for (c in categories) {
            val o = JSObject()
            c.forEach { (k, v) -> o.put(k, v) }
            catArray.put(o)
        }

        val merchArray = JSArray()
        for (m in merchants) {
            val o = JSObject()
            m.forEach { (k, v) -> o.put(k, v) }
            merchArray.put(o)
        }

        val ret = JSObject().apply {
            put("categories", catArray)
            put("merchants", merchArray)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun exportLedgerJson(call: PluginCall) {
        val json = repository.exportLedgerJson()
        call.resolve(JSObject().put("data", json))
    }

    @PluginMethod
    fun updateTransactionCategory(call: PluginCall) {
        val transactionId = call.getString("transactionId") ?: return call.reject("transactionId required")
        val category = call.getString("category") ?: return call.reject("category required")
        val rememberRule = call.getBoolean("rememberRule", true) ?: true

        val success = repository.updateTransactionCategory(transactionId, category, rememberRule)
        if (success) {
            call.resolve(JSObject().put("success", true))
        } else {
            call.reject("Failed to update transaction category")
        }
    }

    @PluginMethod
    fun getCategories(call: PluginCall) {
        val cats = repository.getAllCategories()
        val jsArray = JSArray()
        for (c in cats) {
            jsArray.put(c)
        }
        call.resolve(JSObject().put("categories", jsArray))
    }

    @PluginMethod
    fun simulateNotification(call: PluginCall) {
        val packageName = call.getString("packageName") ?: "com.google.android.apps.nbu.paisa.user"
        val title = call.getString("title") ?: "Google Pay"
        val text = call.getString("text") ?: ""

        if (text.isBlank()) {
            call.reject("Notification text is required")
            return
        }

        try {
            val parsedTxn = ParserRegistry.parse(
                packageName = packageName,
                title = title,
                text = text,
                subText = null,
                timestamp = System.currentTimeMillis()
            )

            if (parsedTxn != null) {
                val (saved, dupResult) = repository.recordTransaction(parsedTxn)

                val ret = JSObject().apply {
                    put("success", true)
                    put("isDuplicate", dupResult.isDuplicate)
                    put("duplicateEvidence", dupResult.evidence.name)
                    put("transactionId", parsedTxn.id)
                    put("amountPaise", parsedTxn.amountPaise)
                    put("formattedRupees", parsedTxn.formattedRupees)
                    put("direction", parsedTxn.direction.name)
                    put("counterparty", parsedTxn.counterpartyName ?: "")
                    put("referenceId", parsedTxn.referenceId ?: "")
                    put("status", parsedTxn.status.name)
                }
                call.resolve(ret)
            } else {
                repository.logEvent(packageName, "SIMULATED_PARSE_FAILED")
                call.reject("Parser could not extract transaction details from provided synthetic text")
            }
        } catch (e: Exception) {
            call.reject("Simulation error: ${e.message}")
        }
    }

    @PluginMethod
    fun purgeFalsePromotions(call: PluginCall) {
        try {
            val purgedCount = repository.purgeFalsePromotions()
            val ret = JSObject().apply {
                put("purgedCount", purgedCount)
                put("success", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to purge promotional data: ${e.message}")
        }
    }

    @PluginMethod
    fun clearTransactions(call: PluginCall) {
        repository.clearAll()
        call.resolve(JSObject().put("cleared", true))
    }
}
