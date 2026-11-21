package com.personalfinance.app.classification

object MerchantDictionary {

    data class MerchantEntry(
        val category: String,
        val confidence: Double = 0.95
    )

    private val DICTIONARY = mapOf(
        // Food & Beverages / Restaurants
        "swiggy" to MerchantEntry("Food and beverages"),
        "zomato" to MerchantEntry("Food and beverages"),
        "mcdonald" to MerchantEntry("Food and beverages"),
        "kfc" to MerchantEntry("Food and beverages"),
        "dominos" to MerchantEntry("Food and beverages"),
        "pizza hut" to MerchantEntry("Food and beverages"),
        "burger king" to MerchantEntry("Food and beverages"),
        "subway" to MerchantEntry("Food and beverages"),
        "haldiram" to MerchantEntry("Food and beverages"),
        "starbucks" to MerchantEntry("Coffee and restaurants"),
        "chai point" to MerchantEntry("Coffee and restaurants"),
        "chaayos" to MerchantEntry("Coffee and restaurants"),
        "cafe coffee day" to MerchantEntry("Coffee and restaurants"),
        "coffee day" to MerchantEntry("Coffee and restaurants"),
        "third wave coffee" to MerchantEntry("Coffee and restaurants"),
        "blue tokai" to MerchantEntry("Coffee and restaurants"),

        // Groceries
        "zepto" to MerchantEntry("Groceries"),
        "blinkit" to MerchantEntry("Groceries"),
        "instamart" to MerchantEntry("Groceries"),
        "bigbasket" to MerchantEntry("Groceries"),
        "dmart" to MerchantEntry("Groceries"),
        "reliance smart" to MerchantEntry("Groceries"),
        "nature's basket" to MerchantEntry("Groceries"),
        "more retail" to MerchantEntry("Groceries"),
        "spencer" to MerchantEntry("Groceries"),

        // Transportation
        "uber" to MerchantEntry("Transportation"),
        "ola" to MerchantEntry("Transportation"),
        "rapido" to MerchantEntry("Transportation"),
        "irctc" to MerchantEntry("Transportation"),
        "makemytrip" to MerchantEntry("Transportation"),
        "redbus" to MerchantEntry("Transportation"),
        "fastag" to MerchantEntry("Transportation"),
        "shell" to MerchantEntry("Transportation"),
        "hpcl" to MerchantEntry("Transportation"),
        "bpcl" to MerchantEntry("Transportation"),
        "indian oil" to MerchantEntry("Transportation"),
        "iocl" to MerchantEntry("Transportation"),

        // Shopping
        "amazon" to MerchantEntry("Shopping"),
        "flipkart" to MerchantEntry("Shopping"),
        "myntra" to MerchantEntry("Shopping"),
        "meesho" to MerchantEntry("Shopping"),
        "ajio" to MerchantEntry("Shopping"),
        "tata cliq" to MerchantEntry("Shopping"),
        "nykaa" to MerchantEntry("Shopping"),
        "croma" to MerchantEntry("Shopping"),
        "reliance digital" to MerchantEntry("Shopping"),
        "decathlon" to MerchantEntry("Shopping"),

        // Subscriptions & Entertainment
        "netflix" to MerchantEntry("Subscriptions"),
        "spotify" to MerchantEntry("Subscriptions"),
        "prime video" to MerchantEntry("Subscriptions"),
        "hotstar" to MerchantEntry("Subscriptions"),
        "disney" to MerchantEntry("Subscriptions"),
        "youtube" to MerchantEntry("Subscriptions"),
        "apple.com/bill" to MerchantEntry("Subscriptions"),
        "google play" to MerchantEntry("Subscriptions"),
        "bookmyshow" to MerchantEntry("Entertainment"),
        "pvr" to MerchantEntry("Entertainment"),
        "inox" to MerchantEntry("Entertainment"),
        "cinepolis" to MerchantEntry("Entertainment"),

        // Utilities
        "bescom" to MerchantEntry("Utilities"),
        "tneb" to MerchantEntry("Utilities"),
        "tata power" to MerchantEntry("Utilities"),
        "adani electricity" to MerchantEntry("Utilities"),
        "airtel" to MerchantEntry("Utilities"),
        "jio" to MerchantEntry("Utilities"),
        "vodafone" to MerchantEntry("Utilities"),
        "vi prepaid" to MerchantEntry("Utilities"),
        "vi postpaid" to MerchantEntry("Utilities"),
        "indane" to MerchantEntry("Utilities"),
        "bharat gas" to MerchantEntry("Utilities"),

        // Medical Expenses
        "apollo" to MerchantEntry("Medical expenses"),
        "pharmeasy" to MerchantEntry("Medical expenses"),
        "1mg" to MerchantEntry("Medical expenses"),
        "medplus" to MerchantEntry("Medical expenses"),
        "netmeds" to MerchantEntry("Medical expenses"),

        // Investments
        "zerodha" to MerchantEntry("Investments"),
        "groww" to MerchantEntry("Investments"),
        "upstox" to MerchantEntry("Investments"),
        "kuvera" to MerchantEntry("Investments"),
        "mf central" to MerchantEntry("Investments"),
        "camsonline" to MerchantEntry("Investments")
    )

    fun lookup(counterparty: String?): MerchantEntry? {
        if (counterparty.isNullOrBlank()) return null
        val normalized = counterparty.trim().lowercase()

        // 1. Direct match
        DICTIONARY[normalized]?.let { return it }

        // 2. Contains match
        for ((key, entry) in DICTIONARY) {
            if (normalized.contains(key)) {
                return entry
            }
        }
        return null
    }
}

