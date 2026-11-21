package com.personalfinance.app.collection

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.personalfinance.app.database.TransactionRepository

object SmsInboxScanner {
    private const val TAG = "RashtSmsScanner"

    data class ScanResult(
        val totalSmsExamined: Int,
        val financialSmsFound: Int,
        val newTransactionsSaved: Int,
        val duplicatesIgnored: Int,
        val errorMessage: String? = null
    )

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun scanInbox(context: Context, maxDays: Int = 30): ScanResult {
        if (!hasPermission(context)) {
            return ScanResult(0, 0, 0, 0, "READ_SMS permission not granted")
        }

        val repository = TransactionRepository(context.applicationContext)
        val cutoffTime = System.currentTimeMillis() - (maxDays.toLong() * 24 * 60 * 60 * 1000)

        var totalExamined = 0
        var financialFound = 0
        var savedCount = 0
        var dupCount = 0

        val urisToTry = listOf(
            Uri.parse("content://sms"),
            Telephony.Sms.Inbox.CONTENT_URI,
            Uri.parse("content://sms/inbox")
        )

        var cursorObtained = false

        for (targetUri in urisToTry) {
            try {
                val projection = arrayOf(
                    "address",
                    "body",
                    "date"
                )

                val cursor = context.contentResolver.query(
                    targetUri,
                    projection,
                    null,
                    null,
                    "date DESC"
                )

                if (cursor != null) {
                    cursorObtained = true
                    cursor.use {
                        val idxAddress = it.getColumnIndex("address")
                        val idxBody = it.getColumnIndex("body")
                        val idxDate = it.getColumnIndex("date")

                        while (it.moveToNext()) {
                            totalExamined++
                            val address = if (idxAddress != -1) it.getString(idxAddress) ?: "" else ""
                            val body = if (idxBody != -1) it.getString(idxBody) ?: "" else ""
                            val date = if (idxDate != -1) it.getLong(idxDate) else System.currentTimeMillis()

                            // If outside date window, skip
                            if (date < cutoffTime) continue
                            if (body.isBlank()) continue

                            val isFinancial = isFinancialSms(address, body)
                            if (!isFinancial) continue

                            financialFound++

                            val parsed = ParserRegistry.parse(
                                packageName = "sms.inbox",
                                title = address,
                                text = body,
                                subText = null,
                                timestamp = date
                            )

                            if (parsed != null) {
                                val (saved, dup) = repository.recordTransaction(parsed)
                                if (saved) {
                                    savedCount++
                                    val formattedAmt = "₹${parsed.amountPaise / 100}.${"%02d".format(parsed.amountPaise % 100)}"
                                    FinanceNotificationListener.addMemoryLog(address, "INBOX_SAVED", "$formattedAmt ${parsed.direction} | ${parsed.counterpartyName ?: address} | Ref: ${parsed.referenceId ?: "none"}")
                                } else if (dup.isDuplicate) {
                                    dupCount++
                                }
                            }
                        }
                    }
                    if (totalExamined > 0) {
                        break // Successfully examined messages from this URI
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Querying $targetUri failed: ${e.message}")
            }
        }

        if (!cursorObtained) {
            val msg = "Could not query device SMS inbox (ContentProvider returned null)"
            FinanceNotificationListener.addMemoryLog("SMS_SYNC", "FAILED", msg)
            return ScanResult(0, 0, 0, 0, msg)
        }

        FinanceNotificationListener.addMemoryLog("SMS_SYNC", "COMPLETED", "Examined $totalExamined SMS, found $financialFound financial, imported $savedCount ($dupCount already recorded)")
        return ScanResult(totalExamined, financialFound, savedCount, dupCount)
    }

    private fun isFinancialSms(sender: String, text: String): Boolean {
        val combined = "$sender $text"

        // Exclude pure OTPs
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
