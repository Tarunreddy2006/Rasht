package com.personalfinance.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.getcapacitor.BridgeActivity
import com.personalfinance.app.bridge.FinanceBridgePlugin
import com.personalfinance.app.collection.FinanceNotificationListener
import com.personalfinance.app.reporting.WeeklyReportWorker
import java.util.concurrent.TimeUnit

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(FinanceBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
        scheduleWeeklyReporting()
        requestBatteryOptimizationExemption()
        checkAndRequestSmsPermissions()
    }

    private fun checkAndRequestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.RECEIVE_SMS)
            }
            if (checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.READ_SMS)
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 101)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val readSmsGranted = grantResults.indices.any { 
                permissions[it] == android.Manifest.permission.READ_SMS && grantResults[it] == android.content.pm.PackageManager.PERMISSION_GRANTED 
            }
            if (readSmsGranted) {
                // Auto-sync bank SMS on permission grant
                Thread {
                    com.personalfinance.app.collection.SmsInboxScanner.scanInbox(applicationContext, 30)
                }.start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndKeepListenerAlive()
    }

    override fun onStart() {
        super.onStart()
        checkAndKeepListenerAlive()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (_: Exception) {}
    }

    private fun checkAndKeepListenerAlive() {
        try {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
            if (enabledPackages.contains(packageName)) {
                if (!FinanceNotificationListener.isConnected) {
                    FinanceNotificationListener.forceRebind(this)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scheduleWeeklyReporting() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "RashtWeeklyReportJob",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
