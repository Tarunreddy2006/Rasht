package com.personalfinance.app.collection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.personalfinance.app.database.TransactionRepository

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RashtSmsReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val repository = TransactionRepository(context.applicationContext)

            // Group multi-part messages by originating address
            val messagesBySender = mutableMapOf<String, StringBuilder>()
            var latestTimestamp = System.currentTimeMillis()

            for (sms in messages) {
                if (sms == null) continue
                val sender = sms.originatingAddress ?: "UNKNOWN_SMS"
                val body = sms.displayMessageBody ?: sms.messageBody ?: ""
                latestTimestamp = sms.timestampMillis

                if (!messagesBySender.containsKey(sender)) {
                    messagesBySender[sender] = StringBuilder()
                }
                messagesBySender[sender]?.append(body)
            }

            for ((sender, bodyBuilder) in messagesBySender) {
                val fullBody = bodyBuilder.toString().trim()
                if (fullBody.isBlank()) continue

                Log.i(TAG, "SMS received from [$sender]: ${fullBody.take(60)}")
                FinanceNotificationListener.addMemoryLog(sender, "SMS_BROADCAST", fullBody.take(100))

                // Fast check for financial relevance
                val isFinancial = hasFinancialContent(sender, fullBody)
                if (!isFinancial) {
                    continue
                }

                val parsed = ParserRegistry.parse(
                    packageName = "sms.receiver",
                    title = sender,
                    text = fullBody,
                    subText = null,
                    timestamp = latestTimestamp
                )

                if (parsed != null) {
                    val (saved, dupResult) = repository.recordTransaction(parsed)
                    if (saved) {
                        val formattedAmt = "₹${parsed.amountPaise / 100}.${"%02d".format(parsed.amountPaise % 100)}"
                        val detail = "$formattedAmt ${parsed.direction} | ${parsed.counterpartyName ?: sender} | Ref: ${parsed.referenceId ?: "none"}"
                        FinanceNotificationListener.addMemoryLog(sender, "SMS_SAVED", detail)
                        repository.logEvent("sms.receiver", "SMS_PARSED_AND_SAVED", parsed.amountPaise)

                        val broadcastIntent = Intent(FinanceNotificationListener.ACTION_NEW_TRANSACTION).apply {
                            putExtra(FinanceNotificationListener.EXTRA_TRANSACTION_ID, parsed.id)
                        }
                        LocalBroadcastManager.getInstance(context.applicationContext).sendBroadcast(broadcastIntent)
                    } else if (dupResult.isDuplicate) {
                        val dupMsg = "₹${parsed.amountPaise / 100} duplicate: ${dupResult.evidence}"
                        FinanceNotificationListener.addMemoryLog(sender, "SMS_DUPLICATE", dupMsg)
                        repository.logEvent("sms.receiver", "DUPLICATE_IGNORED:${dupResult.evidence}", parsed.amountPaise)
                    }
                } else {
                    FinanceNotificationListener.addMemoryLog(sender, "SMS_PARSE_FAILED", fullBody.take(80))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming SMS broadcast", e)
            FinanceNotificationListener.addMemoryLog("SMS", "ERROR", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hasFinancialContent(sender: String, text: String): Boolean {
        val combined = "$sender $text"

        // Exclude pure OTPs without transaction completion
        val hasOtpWord = combined.contains(Regex("""\b(?:OTP\s+is|\bis\s+your\s+OTP|\bOTP:\s*\d+|\bUse\s+OTP|\bOne\s+Time\s+Password)\b""", RegexOption.IGNORE_CASE))
        val hasCompletion = combined.contains("debited", ignoreCase = true) ||
                combined.contains("credited", ignoreCase = true) ||
                combined.contains("spent", ignoreCase = true) ||
                combined.contains("withdrawn", ignoreCase = true)
        if (hasOtpWord && !hasCompletion) return false

        val hasKeyword = combined.contains("debit", ignoreCase = true) ||
                combined.contains("credit", ignoreCase = true) ||
                combined.contains("withdraw", ignoreCase = true) ||
                combined.contains("deposit", ignoreCase = true) ||
                combined.contains("paid", ignoreCase = true) ||
                combined.contains("sent", ignoreCase = true) ||
                combined.contains("received", ignoreCase = true) ||
                combined.contains("transfer", ignoreCase = true) ||
                combined.contains("payment", ignoreCase = true) ||
                combined.contains("upi", ignoreCase = true) ||
                combined.contains("imps", ignoreCase = true) ||
                combined.contains("neft", ignoreCase = true) ||
                combined.contains("a/c", ignoreCase = true) ||
                combined.contains("acct", ignoreCase = true) ||
                combined.contains("account", ignoreCase = true) ||
                combined.contains(Regex("""\bAc\s+[xX0-9]""", RegexOption.IGNORE_CASE)) ||
                combined.contains(Regex("""\bA/C\s+[xX0-9]""", RegexOption.IGNORE_CASE)) ||
                combined.contains(Regex("""\b(?:dr|cr)\b""", RegexOption.IGNORE_CASE))

        val hasCurrency = combined.contains("₹") ||
                combined.contains("Rs", ignoreCase = true) ||
                combined.contains("INR", ignoreCase = true)

        return hasKeyword && hasCurrency
    }
}
