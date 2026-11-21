# Rasht — Personal Financial Intelligence

> **Private, Offline-First Android Application**  
> *Your phone is the server. Your database is local. Your financial data belongs to you.*

---

## 1. Project Overview

**Rasht** is an autonomous personal financial intelligence system built exclusively for Android. Unlike conventional expense trackers that demand tedious manual entry, Rasht monitors incoming UPI payment notifications (Google Pay, PhonePe, Paytm, BHIM) and bank notifications, parses financial transactions into exact integer paise, classifies them using a 6-tier local priority engine, maintains an encrypted double-entry ledger, manages debts and peer lending, and automatically produces weekly financial reports.

### Zero-Cloud Privacy Guarantee
- **No Internet Permission**: `android.permission.INTERNET` is completely omitted from the Android manifest. The application cannot transmit any bytes to the outside world.
- **Hardware-Backed AES-256 Encryption**: Encrypted with SQLCipher 4.6.0; database key is generated and stored exclusively within the Android Keystore hardware security module.
- **No Cloud Backend or Accounts**: No login, no signup, no Firebase, no Supabase, no external telemetry or advertising SDKs.
- **Local Ledger Backup**: Encrypted local-only storage (`android:allowBackup="false"`).

---

## 2. Deliverable APK

- **Final Application APK**: `c:\Indhu\Rasht-final.apk` (19.4 MB)
- **App Name**: **Rasht**
- **Target OS**: Android 7.0+ (API 24 to 34)

### Direct Installation on Android Device

```bash
# Via Android Debug Bridge (ADB):
adb install -r Rasht-final.apk

# Or copy Rasht-final.apk directly to your phone via USB/SD card and tap to install.
```

After installation:
1. Open **Rasht**.
2. If notification access is not yet granted, an **"Access Required"** banner appears at the top.
3. Tap **"Enable Access"** to open Android's *Notification Access* system screen and toggle on **Rasht**.
4. Once enabled, the banner automatically vanishes permanently.

---

## 3. Seven Core Architectural Engines

### Engine 1: Transaction Collection Engine
- **NotificationListenerService**: Background service monitoring:
  - **Google Pay** (`com.google.android.apps.nbu.paisa.user`)
  - **PhonePe** (`com.phonepe.app`)
  - **Paytm** (`net.one97.paytm`)
  - **BHIM** (`in.org.npci.upiapp`)
  - **Major Indian Banks**: HDFC, ICICI, SBI, Axis, Kotak, etc.
- **Dedicated Parser Suite**:
  - `GooglePayParser.kt`: Handles `"Paid ₹X to Y"`, `"Received ₹X from Y"`, UTR references.
  - `PhonePeParser.kt`: Handles `"Payment of ₹X to Y was successful"`, Txn IDs.
  - `PaytmParser.kt`: Handles `"Paid Rs. X to Y"`, `"Received Rs. X from Y"`.
  - `BhimParser.kt`: Handles `"Money sent Rs. X to Y"`.
  - `GenericBankParser.kt`: Handles standard debit/credit SMS/notifications (`"A/c ... debited for Rs X"`).
- **Secondary Sources**:
  - `SmsTransactionReceiver.kt` for optional bank SMS parsing.
  - In-app statement import capability for historical reconciliation.

### Engine 2: Transaction Intelligence Engine
- **Integer Paise Representation**: All currency calculations are stored and computed in integer paise (`1 INR = 100 paise`, `Long`), eliminating all floating-point rounding inaccuracies.
- **State Machine**: Normalizes transactions into `SUCCESS`, `PENDING`, `FAILED`, and `REVERSED`.
- **Intelligent Deduplication (`DuplicateDetector.kt`)**:
  - Tier 1: Exact UTR / Reference ID matching.
  - Tier 2: 120-second cross-channel window deduplication (prevents duplicate entries when a UPI notification and a bank debit SMS arrive for the same payment).
  - Transaction enrichment: Merges secondary metadata (e.g., bank reference from SMS into existing UPI record).

### Engine 3: Automatic Classification Engine
- **6-Tier Hierarchical Classification**:
  1. **User Overrides (`classification_rules`)**: Custom rules created by the user have 100% priority.
  2. **Curated Indian Merchant Dictionary (`MerchantDictionary.kt`)**: Built-in catalogue covering food delivery (Swiggy, Zomato), groceries (Blinkit, Zepto, Instamart), travel (Uber, Ola, Rapido, IRCTC), shopping (Amazon, Flipkart), entertainment (Netflix, Spotify, BookMyShow), utilities (BESCOM, Jio, Airtel), investments (Zerodha, Groww).
  3. **Counterparty History**: Matches previously learned categories for known individuals/merchants.
  4. **Keyword Matcher (`KeywordMatcher.kt`)**: Regex and semantic token matching for typical expense keywords.
  5. **Pattern & Amount Heuristics**: Detects recurring utility or subscription billing patterns.
  6. **Smart Clarification**: Flags ambiguous peer transfers (`P2P`) for user confirmation.
- **Local Learning**: Changing any transaction category prompts to save the rule permanently, immediately adapting future classifications.

### Engine 4: Double-Entry Ledger Engine (`DoubleEntryLedger.kt`)
- **Strict Accounting Invariant**: $\sum \text{Debits} == \sum \text{Credits}$ enforced for every financial event.
- **Chart of Accounts**:
  - `assets:bank`
  - `assets:cash`
  - `assets:receivable:<contact>` (Money lent)
  - `liabilities:payable:<contact>` (Money borrowed)
  - `expenses:<category>`
  - `income:<category>`
  - `equity:opening_balance`
- **Auditability**: Every transaction maintains immutable splits in the `splits` table with real-time balance updates in the `accounts` table.

### Engine 5: Debt & Lending/Borrowing Engine (`DebtManager.kt`)
- **Receivables & Liabilities Tracking**:
  - Money Lent: Creates asset receivable for the counterparty.
  - Money Borrowed: Creates liability payable for the counterparty.
- **Partial Repayments**:
  - Supports multiple partial repayments against an active debt.
  - Computes remaining balance in real time.
  - Auto-settles status to `SETTLED` when remaining balance reaches 0 paise.
- **Peer Transfer Clarification Queue**:
  - When money is sent to or received from a person without clear purchase context, Rasht enqueues a smart clarification card:
    - *"Did you lend ₹500 to Tharun, repay a debt, pay for an expense, or transfer to self?"*
  - Answering the question automatically updates the double-entry splits and debt ledger.

### Engine 6: Financial Reporting & Insights Engine
- **Weekly Report Generator (`WeeklyReportManager.kt`)**:
  - Calculates total spending, income, net savings rate, week-over-week % delta, top spending category, highest single expense, and net debt position.
- **Native Android WorkManager (`WeeklyReportWorker.kt`)**:
  - Background periodic worker scheduled to trigger every Sunday morning.
  - Emits a native Android notification summarizing the week's financial health without opening the app or contacting any server.
- **Direct PDF Export (`jsPDF`)**:
  - Generates formatted, standalone weekly executive financial reports directly in the user interface for saving or printing.

### Engine 7: User Interface & Experience
- **Framework-Free Mobile UI**: Crafted with vanilla HTML, modern CSS, and vanilla JavaScript for sub-millisecond responsiveness and tiny memory footprint.
- **Interactive Modules**:
  1. **Dashboard**: Net worth card, monthly burn rate, pending clarifications carousel, recent transactions.
  2. **Transactions**: Full filterable ledger (All/Debit/Credit), search, category badges, tap-to-inspect modal, category re-assignment.
  3. **Debts**: Tabbed Lent / Borrowed lists, contact balance cards, repayment progress bars, record repayment modal.
  4. **Analytics**: Interactive Chart.js monthly trend line chart, category expense breakdown doughnut chart, category rank list.
  5. **Reports**: In-app weekly digest breakdown and **"Export PDF Report"** button powered by client-side jsPDF.
  6. **Settings**: Dynamic notification permission indicator, SQLCipher 256-bit encryption badge, instant transaction simulator, raw ledger JSON backup export.

---

## 4. Encrypted Database Schema

SQLCipher database (`rasht_secure.db`) schema includes:

```sql
accounts                 -- Chart of accounts (Assets, Liabilities, Expenses, Income, Equity)
transactions             -- Normalized financial records (amount_paise, status, direction, etc.)
splits                   -- Double-entry debit/credit ledger splits
categories               -- Predefined & user categories with icons
classification_rules     -- Locally learned override rules
merchant_dictionary      -- Local merchant to category mapping
contacts                 -- Known counterparties and peer identifiers
debts                    -- Active & settled debt records (LENT, BORROWED)
debt_repayments          -- Repayment history linked to transactions
clarifications           -- Pending ambiguous transaction clarification queue
weekly_reports           -- Stored historical weekly summary aggregates
budgets                  -- Monthly category spending budgets
audit_log                -- Tamper-evident transaction state log
notification_events_log  -- Diagnostic parser and deduplication event log
```

---

## 5. Verification & Test Suite

All unit tests and build tasks pass cleanly:

```powershell
.\gradlew.bat testDebugUnitTest  # 100% Passed (Double-entry, Debt math, Parsers, Deduplication)
.\gradlew.bat assembleDebug      # BUILD SUCCESSFUL (19.4 MB APK generated)
```

Verified Test Scenarios:
- `GooglePayParser`: Debit & Credit parsing, UTR extraction, paise conversions.
- `PhonePeParser`: Non-greedy regex, Txn reference extraction, merchant names.
- `PaytmParser` & `BhimParser`: Indian rupee formats (`Rs.`, `₹`, comma formatting).
- `DuplicateDetector`: Exact reference deduplication, 120s cross-channel deduplication.
- `DoubleEntryLedger`: Mathematical balance invariant ($\sum \text{Debits} == \sum \text{Credits}$).
- `DebtManager`: Principal minus repayments calculation, zero-balance auto-settlement.
