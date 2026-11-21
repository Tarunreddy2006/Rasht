package com.personalfinance.app.reporting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalfinance.app.database.AppDatabaseHelper

class WeeklyReportWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dbHelper = AppDatabaseHelper(context)
            val db = dbHelper.getWritableEncryptedDb()
            WeeklyReportManager.generateWeeklyReport(context, db)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}

