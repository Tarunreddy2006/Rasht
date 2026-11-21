package com.personalfinance.app.classification

object KeywordMatcher {

    data class KeywordRule(
        val keywords: List<String>,
        val category: String,
        val confidence: Double = 0.75
    )

    private val RULES = listOf(
        KeywordRule(
            listOf("coffee", "cafe", "tea", "chai", "bakery", "bistro", "roastery"),
            "Coffee and restaurants"
        ),
        KeywordRule(
            listOf("restaurant", "hotel", "kitchen", "biryani", "dhaba", "sweets", "tiffin", "bhojanalaya", "caterers", "snack"),
            "Food and beverages"
        ),
        KeywordRule(
            listOf("supermarket", "hypermarket", "grocery", "groceries", "mart", "provision", "kirana", "vegetable", "fruits", "dairy", "milk"),
            "Groceries"
        ),
        KeywordRule(
            listOf("fuel", "petrol", "diesel", "auto", "taxi", "cab", "metro", "toll", "parking", "travel"),
            "Transportation"
        ),
        KeywordRule(
            listOf("pharmacy", "chemist", "clinic", "hospital", "doctor", "dental", "medicals", "diagnostics", "lab"),
            "Medical expenses"
        ),
        KeywordRule(
            listOf("electricity", "water bill", "broadband", "wifi", "gas agency", "cylinder", "recharge", "postpaid", "dth"),
            "Utilities"
        ),
        KeywordRule(
            listOf("rent", "house rent", "flat rent", "pg", "hostel", "society maintenance"),
            "Rent"
        ),
        KeywordRule(
            listOf("school", "college", "tuition", "classes", "academy", "institute", "course", "fees", "stationery", "books"),
            "Education"
        ),
        KeywordRule(
            listOf("cinema", "movies", "theatre", "game", "gaming", "amusement", "bowling"),
            "Entertainment"
        ),
        KeywordRule(
            listOf("cloth", "apparel", "textile", "jeweller", "fashion", "boutique", "footwear", "shoes", "tailor"),
            "Shopping"
        )
    )

    fun match(text: String?): KeywordRule? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()

        for (rule in RULES) {
            for (kw in rule.keywords) {
                // Word boundary or containment check
                if (lower.contains(kw)) {
                    return rule
                }
            }
        }
        return null
    }
}

