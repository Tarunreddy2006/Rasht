// Bridge wrapper for Capacitor Native Plugin
const FinanceBridge = {
  isAvailable() {
    return window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FinanceBridge;
  },

  async checkPermission() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.checkNotificationPermission();
    }
    return { enabled: true, serviceConnected: true };
  },

  async requestPermission() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.requestNotificationPermission();
    }
    alert("Running in browser simulator. On Android, this opens Notification Access settings.");
    return { opened: true };
  },

  async rebindService() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.rebindNotificationService();
    }
    return { enabled: true, serviceConnected: true };
  },

  async checkSmsPermission() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.checkSmsPermission();
    }
    return { receiveSms: true, readSms: true, allGranted: true };
  },

  async requestSmsPermission() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.requestSmsPermission();
    }
    alert("Running in simulator. On Android, this requests SMS permissions.");
    return { requested: true };
  },

  async syncInboxSms(days = 30) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.syncInboxSms({ days });
    }
    return { totalExamined: 0, financialFound: 0, newSaved: 0, duplicates: 0 };
  },

  async getTransactions(limit = 100) {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getTransactions({ limit });
      return res.transactions || [];
    }
    return window._simulatedTransactions || [];
  },

  async getDiagnostics() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.getDiagnostics();
    }
    const txns = window._simulatedTransactions || [];
    let debit = 0;
    let credit = 0;
    txns.forEach(t => {
      if (t.direction === 'DEBIT') debit += t.amountPaise;
      if (t.direction === 'CREDIT') credit += t.amountPaise;
    });
    return {
      permissionGranted: true,
      listenerConnected: true,
      lastCapturedTime: txns.length ? txns[0].timestamp : 0,
      totalCapturedCount: txns.length,
      totalDebitPaise: debit,
      totalCreditPaise: credit,
      duplicateCount: 0,
      learnedRulesCount: 0,
      outstandingReceivablesPaise: 0,
      outstandingLiabilitiesPaise: 0,
      bankBalancePaise: credit - debit,
      netWorthPaise: credit - debit,
      pendingClarifications: 0,
      supportedPackages: ["com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "Bank SMS / Apps"]
    };
  },

  async getPendingClarifications() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getPendingClarifications();
      return res.clarifications || [];
    }
    return [];
  },

  async resolveClarification(clarificationId, answer) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.resolveClarification({ clarificationId, answer });
    }
    return { success: true };
  },

  async getDebts(type = null) {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getDebts({ type });
      return res.debts || [];
    }
    return window._simulatedDebts || [];
  },

  async createManualDebt(contactName, type, amountPaise, notes = "") {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.createManualDebt({ contactName, type, amountPaise, notes });
    }
    if (!window._simulatedDebts) window._simulatedDebts = [];
    const newDebt = {
      id: "debt-" + Date.now(),
      contactName,
      type,
      principalPaise: amountPaise,
      balancePaise: amountPaise,
      formattedPrincipal: "₹" + (amountPaise / 100).toFixed(2),
      formattedBalance: "₹" + (amountPaise / 100).toFixed(2),
      status: "ACTIVE",
      createdAt: Date.now()
    };
    window._simulatedDebts.unshift(newDebt);
    return { success: true, debtId: newDebt.id };
  },

  async recordDebtRepayment(debtId, amountPaise, notes = "") {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.recordDebtRepayment({ debtId, amountPaise, notes });
    }
    const d = (window._simulatedDebts || []).find(x => x.id === debtId);
    if (d) {
      d.balancePaise = Math.max(0, d.balancePaise - amountPaise);
      d.formattedBalance = "₹" + (d.balancePaise / 100).toFixed(2);
      if (d.balancePaise === 0) d.status = "SETTLED";
    }
    return { success: true };
  },

  async getFinancialNotes() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getFinancialNotes();
      return res.notes || [];
    }
    return window._simulatedNotes || [];
  },

  async addFinancialNote(title, content) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.addFinancialNote({ title, content });
    }
    if (!window._simulatedNotes) window._simulatedNotes = [];
    window._simulatedNotes.unshift({ id: "note-" + Date.now(), title, content, createdAt: Date.now() });
    return { success: true };
  },

  async deleteFinancialNote(id) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.deleteFinancialNote({ id });
    }
    window._simulatedNotes = (window._simulatedNotes || []).filter(n => n.id !== id);
    return { success: true };
  },

  async generateWeeklyReport() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.generateWeeklyReport();
      return res.report;
    }
    return null;
  },

  async getLatestWeeklyReport() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getLatestWeeklyReport();
      return res.report;
    }
    return null;
  },

  async getAnalyticsSummary() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.getAnalyticsSummary();
    }
    return { categories: [], merchants: [] };
  },

  async exportLedgerJson() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.exportLedgerJson();
      return res.data;
    }
    return JSON.stringify({ transactions: window._simulatedTransactions || [] }, null, 2);
  },

  async updateCategory(transactionId, category, rememberRule = true) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.updateTransactionCategory({
        transactionId,
        category,
        rememberRule
      });
    }
    const tx = (window._simulatedTransactions || []).find(t => t.id === transactionId);
    if (tx) {
      tx.category = category;
      tx.confidenceScore = 1.0;
      tx.clarificationState = 'RESOLVED';
    }
    return { success: true };
  },

  async getCategories() {
    if (this.isAvailable()) {
      const res = await window.Capacitor.Plugins.FinanceBridge.getCategories();
      return res.categories || [];
    }
    return [
      "Food and beverages", "Coffee and restaurants", "Groceries", "Transportation",
      "Shopping", "Education", "Entertainment", "Subscriptions", "Utilities",
      "Medical expenses", "Rent", "Personal transfers", "Lending", "Borrowing",
      "Repayments", "Gifts", "Investments", "Other"
    ];
  },

  async simulateNotification(packageName, title, text) {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.simulateNotification({
        packageName,
        title,
        text
      });
    }

    const parsed = simulateJsParser(packageName, title, text);
    if (!window._simulatedTransactions) window._simulatedTransactions = [];
    window._simulatedTransactions.unshift(parsed);
    return parsed;
  },

  async purgeFalsePromotions() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.purgeFalsePromotions();
    }
    return { purgedCount: 0, success: true };
  },

  async clearTransactions() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.clearTransactions();
    }
    window._simulatedTransactions = [];
    window._simulatedDebts = [];
    window._simulatedNotes = [];
    return { cleared: true };
  },

  async getDebugLog() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.getDebugLog();
    }
    return {
      serviceConnected: true,
      totalReceived: 0,
      lastCapturedTime: 0,
      lastNotificationTime: 0,
      lastError: "",
      events: []
    };
  },

  async clearDebugLog() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.clearDebugLog();
    }
    return { cleared: true };
  },

  async checkBatteryOptimization() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.checkBatteryOptimization();
    }
    return { ignoringBatteryOptimizations: true };
  },

  async requestIgnoreBatteryOptimizations() {
    if (this.isAvailable()) {
      return await window.Capacitor.Plugins.FinanceBridge.requestIgnoreBatteryOptimizations();
    }
    alert("Running in browser simulator. On Android, this opens battery optimization settings.");
    return { opened: true };
  },

  addListener(callback) {
    if (this.isAvailable()) {
      window.Capacitor.Plugins.FinanceBridge.addListener('onTransactionCaptured', callback);
    }
  }
};

function simulateJsParser(pkg, title, text) {
  const combined = `${title} ${text}`;
  const amtMatch = combined.match(/(?:₹|Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{1,2})?)/i);
  const amountStr = amtMatch ? amtMatch[1].replace(/,/g, '') : "0";
  const paise = Math.round(parseFloat(amountStr) * 100);

  const isDebit = combined.toLowerCase().includes("paid") || combined.toLowerCase().includes("sent") || combined.toLowerCase().includes("debited");
  const isCredit = combined.toLowerCase().includes("received") || combined.toLowerCase().includes("credited");

  return {
    id: "sim-" + Date.now(),
    sourcePackage: pkg,
    amountPaise: paise,
    amountRupees: paise / 100,
    formattedRupees: "₹" + (paise / 100).toFixed(2),
    direction: isDebit ? "DEBIT" : (isCredit ? "CREDIT" : "TRANSFER"),
    status: "SUCCESS",
    counterpartyName: title || "Merchant",
    referenceId: "SIM" + Math.floor(Math.random() * 1000000000),
    category: "Food and beverages",
    confidenceScore: 0.95,
    timestamp: Date.now()
  };
}
