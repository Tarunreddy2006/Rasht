package com.personalfinance.app.collection

import android.app.Notification
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.personalfinance.app.database.AppDatabaseHelper
import com.personalfinance.app.database.TransactionRepository
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class FinanceNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "RashtListener"
        const val ACTION_NEW_TRANSACTION = "com.personalfinance.app.ACTION_NEW_TRANSACTION"
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastCapturedTime: Long = 0
            private set

        @Volatile
        var lastNotificationTime: Long = 0
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var totalNotificationsReceived: Long = 0
            private set

        // In-memory circular buffer of last 50 debug log events
        val inMemoryLogs = ConcurrentLinkedQueue<Map<String, Any>>()
        private const val MAX_LOGS = 50

        fun addMemoryLog(pkg: String, status: String, detail: String) {
            val entry = mapOf<String, Any>(
                "id" to UUID.randomUUID().toString(),
                "package" to pkg,
                "status" to status,
                "detail" to detail,
                "timestamp" to System.currentTimeMillis()
            )
            inMemoryLogs.add(entry)
            while (inMemoryLogs.size > MAX_LOGS) {
                inMemoryLogs.poll()
            }
        }

        fun forceRebind(context: Context) {
            try {
                val component = ComponentName(context, FinanceNotificationListener::class.java)
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                addMemoryLog("SYSTEM", "FORCE_REBIND", "Component state toggled to trigger service rebind")
            } catch (e: Exception) {
                Log.e(TAG, "forceRebind failed", e)
                addMemoryLog("SYSTEM", "REBIND_ERROR", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private lateinit var repository: TransactionRepository
    private lateinit var dbHelper: AppDatabaseHelper

    override fun onCreate() {
        super.onCreate()
        repository = TransactionRepository(applicationContext)
        dbHelper = AppDatabaseHelper(applicationContext)
        Log.i(TAG, "FinanceNotificationListener created")
        addMemoryLog("SYSTEM", "CREATED", "FinanceNotificationListener service created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "Listener CONNECTED")
        addMemoryLog("SYSTEM", "CONNECTED", "Service successfully bound to Android NotificationManager")
        logDebug("SYSTEM", "CONNECTED", "Service connected to NotificationManager")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "Listener DISCONNECTED")
        addMemoryLog("SYSTEM", "DISCONNECTED", "Service disconnected from NotificationManager")
        logDebug("SYSTEM", "DISCONNECTED", "Service disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, FinanceNotificationListener::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        totalNotificationsReceived++
        lastNotificationTime = System.currentTimeMillis()

        // Skip our own notifications
        if (packageName == applicationContext.packageName) return

        // Skip ongoing persistent notifications (music players, download bars, etc.)
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return
        }

        // STRICT SCOPE: Only monitor SMS messages and PhonePe notifications.
        // Ignore all other application notifications completely (WhatsApp, Instagram, Zomato, Swiggy, etc.)
        val isMessagingApp = packageName.contains("messaging", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("sms", ignoreCase = true) ||
                packageName == "com.google.android.apps.messaging" ||
                packageName == "com.samsung.android.messaging" ||
                packageName == "com.android.mms" ||
                packageName == "com.oneplus.mms" ||
                packageName == "com.coloros.mms" ||
                packageName == "com.heytap.mms" ||
                packageName == "com.miui.sms" ||
                packageName == "com.vivo.mms" ||
                packageName == "com.bbk.mms" ||
                packageName == "com.truecaller" ||
                packageName == "com.microsoft.android.smsorganizer"

        val isPhonePe = packageName == "com.phonepe.app"

        if (!isMessagingApp && !isPhonePe) {
            return
        }

        val extras = notification.extras

        // ===== EXTRACT ALL POSSIBLE TEXT FROM THE NOTIFICATION =====
        val extractedTexts = mutableMapOf<String, String>()

        // 1. Standard extras
        tryExtract(extras, Notification.EXTRA_TITLE, extractedTexts, "title")
        tryExtract(extras, Notification.EXTRA_TITLE_BIG, extractedTexts, "titleBig")
        tryExtract(extras, Notification.EXTRA_TEXT, extractedTexts, "text")
        tryExtract(extras, Notification.EXTRA_BIG_TEXT, extractedTexts, "bigText")
        tryExtract(extras, Notification.EXTRA_SUB_TEXT, extractedTexts, "subText")
        tryExtract(extras, Notification.EXTRA_SUMMARY_TEXT, extractedTexts, "summaryText")
        tryExtract(extras, Notification.EXTRA_INFO_TEXT, extractedTexts, "infoText")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val convTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                if (!convTitle.isNullOrBlank()) extractedTexts["convTitle"] = convTitle.toString()
            } catch (_: Exception) {}
        }

        // 2. tickerText
        notification.tickerText?.let {
            if (it.isNotBlank()) extractedTexts["ticker"] = it.toString()
        }

        // 3. MessagingStyle messages (critical for Google Messages / OEM SMS apps)
        val messagingMessages = mutableListOf<String>()
        try {
            val msgStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (msgStyle != null) {
                val msgs = msgStyle.messages
                for (m in msgs) {
                    val t = m.text?.toString()
                    if (!t.isNullOrBlank()) messagingMessages.add(t)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MessagingStyle extraction failed: ${e.message}")
        }

        // 4. EXTRA_MESSAGES raw Bundle array fallback
        if (messagingMessages.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val msgArray: Array<Parcelable>? = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (msgArray != null) {
                    for (item in msgArray) {
                        if (item is Bundle) {
                            val t = item.getCharSequence("text")?.toString()
                            if (!t.isNullOrBlank()) messagingMessages.add(t)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "EXTRA_MESSAGES extraction failed: ${e.message}")
            }
        }

        // 5. EXTRA_TEXT_LINES (InboxStyle)
        val textLines = mutableListOf<String>()
        try {
            val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null) {
                for (l in lines) {
                    val t = l?.toString()
                    if (!t.isNullOrBlank()) textLines.add(t)
                }
            }
        } catch (_: Exception) {}

        // Best title
        val title = extractedTexts["title"]
            ?: extractedTexts["titleBig"]
            ?: extractedTexts["convTitle"]

        val subText = extractedTexts["subText"]
            ?: extractedTexts["summaryText"]

        // Determine if this is an SMS or messaging app
        val isRecognizedPackage = ParserRegistry.isSupportedPackage(packageName)
        val isMessagingApp = packageName.contains("messaging", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("sms", ignoreCase = true) ||
                packageName == "com.google.android.apps.messaging" ||
                packageName == "com.samsung.android.messaging" ||
                packageName == "com.android.mms" ||
                packageName == "com.oneplus.mms" ||
                packageName == "com.coloros.mms" ||
                packageName == "com.heytap.mms" ||
                packageName == "com.miui.sms" ||
                packageName == "com.vivo.mms" ||
                packageName == "com.bbk.mms" ||
                packageName == "com.truecaller" ||
                packageName == "com.microsoft.android.smsorganizer"

        val isPhonePe = packageName == "com.phonepe.app"

        // STRICT SCOPE: Only monitor SMS messages and PhonePe notifications.
        // Ignore all other application notifications completely.
        if (!isMessagingApp && !isPhonePe) {
            return
        }

        // Build list of text candidates to test for financial transactions
        val candidateBodies = mutableListOf<String>()

        if (messagingMessages.isNotEmpty()) {
            // Newest message first
            for (m in messagingMessages.reversed()) {
                if (!candidateBodies.contains(m)) candidateBodies.add(m)
            }
            // Also add full joined text
            val joined = messagingMessages.joinToString(" ")
            if (!candidateBodies.contains(joined)) candidateBodies.add(joined)
        }

        // Add standard body texts
        extractedTexts["bigText"]?.let { if (!candidateBodies.contains(it)) candidateBodies.add(it) }
        extractedTexts["text"]?.let { if (!candidateBodies.contains(it)) candidateBodies.add(it) }
        if (textLines.isNotEmpty()) {
            for (l in textLines.reversed()) {
                if (!candidateBodies.contains(l)) candidateBodies.add(l)
            }
        }
        extractedTexts["ticker"]?.let { if (!candidateBodies.contains(it)) candidateBodies.add(it) }

        if (candidateBodies.isEmpty()) {
            return
        }

        // Log receipt if it comes from an SMS app or a supported finance app
        if (isMessagingApp || isRecognizedPackage) {
            val preview = candidateBodies.firstOrNull()?.take(100) ?: ""
            addMemoryLog(packageName, "RECEIVED", "title=[$title] body=[$preview]")
        }

        // Scan candidate bodies for financial transaction
        var handledAny = false

        for (bodyCandidate in candidateBodies) {
            val allText = "${title.orEmpty()} $bodyCandidate ${subText.orEmpty()}".trim()

            // Skip pure OTP messages that have no debit or credit completion
            if (isPureOtp(allText)) {
                if (isMessagingApp || isRecognizedPackage) {
                    addMemoryLog(packageName, "OTP_SKIPPED", bodyCandidate.take(80))
                }
                continue
            }

            val hasFinancialKeyword = allText.contains("debit", ignoreCase = true) ||
                    allText.contains("credit", ignoreCase = true) ||
                    allText.contains("withdraw", ignoreCase = true) ||
                    allText.contains("deposit", ignoreCase = true) ||
                    allText.contains("paid", ignoreCase = true) ||
                    allText.contains("sent", ignoreCase = true) ||
                    allText.contains("received", ignoreCase = true) ||
                    allText.contains("transfer", ignoreCase = true) ||
                    allText.contains("payment", ignoreCase = true) ||
                    allText.contains("upi", ignoreCase = true) ||
                    allText.contains("imps", ignoreCase = true) ||
                    allText.contains("neft", ignoreCase = true) ||
                    allText.contains("a/c", ignoreCase = true) ||
                    allText.contains("acct", ignoreCase = true) ||
                    allText.contains("account", ignoreCase = true) ||
                    allText.contains(Regex("""\bAc\s+[xX0-9]""", RegexOption.IGNORE_CASE)) ||
                    allText.contains(Regex("""\bA/C\s+[xX0-9]""", RegexOption.IGNORE_CASE)) ||
                    allText.contains(Regex("""\b(?:dr|cr)\b""", RegexOption.IGNORE_CASE))

            val hasCurrency = allText.contains("₹") ||
                    allText.contains("Rs", ignoreCase = true) ||
                    allText.contains("INR", ignoreCase = true)

            if (!hasFinancialKeyword || !hasCurrency) {
                continue
            }

            // Attempt parse
            try {
                val parsedTxn = ParserRegistry.parse(
                    packageName = packageName,
                    title = title,
                    text = bodyCandidate,
                    subText = subText,
                    timestamp = sbn.postTime
                )

                if (parsedTxn != null) {
                    val (saved, dupResult) = repository.recordTransaction(parsedTxn)
                    if (saved) {
                        lastCapturedTime = parsedTxn.timestamp
                        val formattedAmt = "₹${parsedTxn.amountPaise / 100}.${"%02d".format(parsedTxn.amountPaise % 100)}"
                        val logMsg = "$formattedAmt ${parsedTxn.direction} | ${parsedTxn.counterpartyName ?: "unknown"} | Ref: ${parsedTxn.referenceId ?: "none"}"
                        
                        addMemoryLog(packageName, "SAVED", logMsg)
                        logDebug(packageName, "SAVED", logMsg)
                        repository.logEvent(packageName, "PARSED_AND_SAVED", parsedTxn.amountPaise)

                        val intent = Intent(ACTION_NEW_TRANSACTION).apply {
                            putExtra(EXTRA_TRANSACTION_ID, parsedTxn.id)
                        }
                        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
                        handledAny = true
                        break // Parsed successfully, avoid double-counting same notification candidate
                    } else if (dupResult.isDuplicate) {
                        val dupMsg = "₹${parsedTxn.amountPaise / 100} duplicate: ${dupResult.evidence}"
                        addMemoryLog(packageName, "DUPLICATE", dupMsg)
                        logDebug(packageName, "DUPLICATE", dupMsg)
                        repository.logEvent(packageName, "DUPLICATE_IGNORED:${dupResult.evidence}", parsedTxn.amountPaise)
                        handledAny = true
                        break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                val errMsg = "${e.javaClass.simpleName}: ${e.message}"
                addMemoryLog(packageName, "PARSE_ERROR", errMsg)
                logDebug(packageName, "EXCEPTION", errMsg)
                Log.e(TAG, "Parse exception", e)
            }
        }

        if (!handledAny && (isMessagingApp || isRecognizedPackage)) {
            val preview = candidateBodies.firstOrNull()?.take(80) ?: ""
            addMemoryLog(packageName, "NO_PARSE_MATCH", "Text did not match transaction patterns: $preview")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed
    }

    private fun isPureOtp(text: String): Boolean {
        val hasOtpWord = text.contains(Regex("""\b(?:OTP\s+is|\bis\s+your\s+OTP|\bOTP:\s*\d+|\bUse\s+OTP|\bOne\s+Time\s+Password)\b""", RegexOption.IGNORE_CASE))
        val hasCompletion = text.contains("debited", ignoreCase = true) ||
                text.contains("credited", ignoreCase = true) ||
                text.contains("spent", ignoreCase = true) ||
                text.contains("withdrawn", ignoreCase = true) ||
                text.contains("successful", ignoreCase = true)
        return hasOtpWord && !hasCompletion
    }

    private fun tryExtract(extras: Bundle?, key: String, dest: MutableMap<String, String>, label: String) {
        try {
            val value = extras?.getCharSequence(key)
            if (!value.isNullOrBlank()) {
                dest[label] = value.toString()
            }
        } catch (_: Exception) {}
    }

    private fun logDebug(pkg: String, status: String, detail: String) {
        try {
            val db = dbHelper.getWritableEncryptedDb()
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("source_package", pkg)
                put("parse_status", "[$status] $detail")
                put("created_at", System.currentTimeMillis())
            }
            db.insert(AppDatabaseHelper.TABLE_RAW_EVENTS, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log to DB", e)
        }
    }
}
