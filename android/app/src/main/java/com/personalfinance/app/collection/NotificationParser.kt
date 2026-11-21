package com.personalfinance.app.collection

import com.personalfinance.app.model.ParsedTransaction

interface NotificationParser {
    val supportedPackages: Set<String>
    val displayName: String

    fun canParse(packageName: String, title: String?, text: String?): Boolean
    fun parse(packageName: String, title: String?, text: String?, subText: String?, timestamp: Long): ParsedTransaction?
}

