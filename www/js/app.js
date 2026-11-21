// Main Application Controller for Rasht (Phases 1 - 7 Complete)

let activeTab = "dashboard";
let currentDebtFilter = "LENT";
let currentTxFilter = "ALL";
let cachedCategories = [];
let selectedTransactionForEdit = null;
let activeRepayDebtId = null;

let categoryChartInstance = null;
let cashflowChartInstance = null;
let latestReportData = null;

document.addEventListener("DOMContentLoaded", () => {
  initApp();
});

async function initApp() {
  setupNavigation();
  setupFilterPills();
  setupSearch();
  setupSimulatorButtons();

  // Auto-clean any past promotional false credits on boot
  try {
    await FinanceBridge.purgeFalsePromotions();
  } catch (_e) {}

  await loadCategories();
  await refreshAll();

  // Realtime native event listener
  FinanceBridge.addListener((event) => {
    if (event && event.isDuplicate) {
      showToast("Duplicate transaction detected & prevented!");
    }
    refreshAll();
  });

  // Re-check permissions when returning from Settings
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refreshAll();
  });
  window.addEventListener("focus", () => refreshAll());

  // Refresh button in header
  const btnHeaderRefresh = document.getElementById("btnHeaderRefresh");
  if (btnHeaderRefresh) {
    btnHeaderRefresh.addEventListener("click", async () => {
      btnHeaderRefresh.classList.add("spinning");
      await FinanceBridge.rebindService();
      await refreshAll();
      showToast("Ledger & balances refreshed");
      setTimeout(() => btnHeaderRefresh.classList.remove("spinning"), 600);
    });
  }

  // Permission button
  const btnPerm = document.getElementById("btnGrantPermission");
  if (btnPerm) btnPerm.addEventListener("click", () => FinanceBridge.requestPermission());

  // Report generation buttons
  const btnGen = document.getElementById("btnGenerateReportNow");
  if (btnGen) btnGen.addEventListener("click", () => generateWeeklyDigest());

  const btnPdf = document.getElementById("btnExportPdf");
  if (btnPdf) btnPdf.addEventListener("click", () => exportReportPdf());

  // Settings buttons
  const btnExp = document.getElementById("btnExportJson");
  if (btnExp) btnExp.addEventListener("click", () => exportLedgerJson());

  const btnSettingsPerm = document.getElementById("btnSettingsPerm");
  if (btnSettingsPerm) btnSettingsPerm.addEventListener("click", () => FinanceBridge.requestPermission());

  const btnSettingsRebind = document.getElementById("btnSettingsRebind");
  if (btnSettingsRebind) btnSettingsRebind.addEventListener("click", async () => {
    const res = await FinanceBridge.rebindService();
    showToast(res && res.serviceConnected ? "Listener Reconnected Successfully!" : "Service Rebind Requested");
    refreshAll();
  });

  const btnSettingsBattery = document.getElementById("btnSettingsBattery");
  if (btnSettingsBattery) btnSettingsBattery.addEventListener("click", async () => {
    await FinanceBridge.requestIgnoreBatteryOptimizations();
    setTimeout(refreshAll, 1000);
  });

  const btnRefreshDebugLog = document.getElementById("btnRefreshDebugLog");
  if (btnRefreshDebugLog) btnRefreshDebugLog.addEventListener("click", async () => {
    await renderDebugLog();
    showToast("Activity log refreshed");
  });

  const btnClearDebugLog = document.getElementById("btnClearDebugLog");
  if (btnClearDebugLog) btnClearDebugLog.addEventListener("click", async () => {
    await FinanceBridge.clearDebugLog();
    await renderDebugLog();
    showToast("Activity log cleared");
  });

  // SMS Scan buttons
  const btnScanInboxSms = document.getElementById("btnScanInboxSms");
  if (btnScanInboxSms) btnScanInboxSms.addEventListener("click", () => performSmsScan());

  const btnSettingsSyncSms = document.getElementById("btnSettingsSyncSms");
  if (btnSettingsSyncSms) btnSettingsSyncSms.addEventListener("click", () => performSmsScan());

  const btnSettingsSmsPerm = document.getElementById("btnSettingsSmsPerm");
  if (btnSettingsSmsPerm) btnSettingsSmsPerm.addEventListener("click", async () => {
    await FinanceBridge.requestSmsPermission();
    setTimeout(refreshAll, 1200);
  });

  const btnCleanPromoSpam = document.getElementById("btnCleanPromoSpam");
  if (btnCleanPromoSpam) btnCleanPromoSpam.addEventListener("click", async () => {
    btnCleanPromoSpam.textContent = "Cleaning...";
    try {
      const res = await FinanceBridge.purgeFalsePromotions();
      const count = res?.purgedCount ?? 0;
      showToast(`Cleaned ${count} false promotional credits! Balances corrected.`);
      await refreshAll();
    } catch (e) {
      showToast("Clean failed: " + e);
    } finally {
      btnCleanPromoSpam.textContent = "Clean Spam";
    }
  });

  const btnPurge = document.getElementById("btnPurgeData");
  if (btnPurge) btnPurge.addEventListener("click", async () => {
    if (confirm("WARNING: This will permanently wipe all local encrypted transactions and debt records!")) {
      await FinanceBridge.clearTransactions();
      showToast("Ledger purged");
      refreshAll();
    }
  });

  // Manual debt modal buttons
  const btnSaveDebt = document.getElementById("btnSaveDebt");
  if (btnSaveDebt) btnSaveDebt.addEventListener("click", () => saveManualDebt());

  const btnSaveRepay = document.getElementById("btnSaveRepayment");
  if (btnSaveRepay) btnSaveRepay.addEventListener("click", () => saveDebtRepayment());
}

async function refreshAll() {
  await refreshDiagnostics();
  await refreshClarifications();
  await refreshTransactions();
  await refreshDebts();
  if (activeTab === "analytics") await renderAnalytics();
  if (activeTab === "settings") await renderDebugLog();
}

function setupNavigation() {
  const navItems = document.querySelectorAll(".nav-item");
  navItems.forEach(item => {
    item.addEventListener("click", () => {
      const targetTab = item.getAttribute("data-tab");
      switchTab(targetTab);
    });
  });
}

window.switchTab = function(tabName) {
  activeTab = tabName;
  document.querySelectorAll(".nav-item").forEach(el => {
    el.classList.toggle("active", el.getAttribute("data-tab") === tabName);
  });
  document.querySelectorAll(".tab-pane").forEach(el => {
    el.classList.toggle("active", el.id === `tab-${tabName}`);
  });

  if (tabName === "analytics") renderAnalytics();
  if (tabName === "reports" && !latestReportData) loadLatestReport();
  if (tabName === "settings") renderDebugLog();
};

async function renderDebugLog() {
  const container = document.getElementById("debugLogContainer");
  const statsBox = document.getElementById("debugLogStats");
  if (!container || !statsBox) return;

  try {
    const data = await FinanceBridge.getDebugLog();
    const batteryStatus = await FinanceBridge.checkBatteryOptimization();

    const isConnected = data.serviceConnected;
    const totalRecv = data.totalReceived || 0;
    const lastCap = data.lastCapturedTime ? new Date(data.lastCapturedTime).toLocaleTimeString() : "None";
    const lastNotif = data.lastNotificationTime ? new Date(data.lastNotificationTime).toLocaleTimeString() : "None";
    const battExempt = batteryStatus ? batteryStatus.ignoringBatteryOptimizations : false;

    // Update battery subtext in settings
    const battSub = document.getElementById("settingsBatterySub");
    if (battSub) {
      battSub.textContent = battExempt ? "Exempted (Protected from OEM background kill)" : "Optimized (May be frozen by Realme UI!)";
      battSub.style.color = battExempt ? "#10b981" : "#f59e0b";
    }

    statsBox.innerHTML = `
      <div style="display:flex; justify-content:space-between; margin-bottom:2px;">
        <span>Service: <b style="color:${isConnected ? '#10b981' : '#f43f5e'}">${isConnected ? 'CONNECTED' : 'DISCONNECTED'}</b></span>
        <span>Notifications Seen: <b>${totalRecv}</b></span>
      </div>
      <div style="display:flex; justify-content:space-between;">
        <span>Last Seen: <b>${lastNotif}</b></span>
        <span>Last Captured: <b>${lastCap}</b></span>
      </div>
      ${data.lastError ? `<div style="color:#f43f5e; margin-top:3px;">Last Error: ${escapeHtml(data.lastError)}</div>` : ''}
    `;

    const events = data.events || [];
    if (events.length === 0) {
      container.innerHTML = `<div style="color:var(--text-muted); text-align:center; padding:12px;">No notification events recorded yet.<br>When bank SMS notifications arrive, details appear here instantly.</div>`;
      return;
    }

    container.innerHTML = events.slice(0, 40).map(ev => {
      const timeStr = ev.createdAt ? new Date(ev.createdAt).toLocaleTimeString() : "";
      const statusText = ev.status || "";
      const statusColor = statusText.includes("SAVED") ? "#10b981" :
                          statusText.includes("DUPLICATE") ? "#f59e0b" :
                          statusText.includes("ERROR") || statusText.includes("EXCEPTION") ? "#f43f5e" :
                          statusText.includes("OTP") ? "#94a3b8" : "#38bdf8";

      const pkgShort = ev.sourcePackage ? ev.sourcePackage.split('.').slice(-2).join('.') : "sys";
      const detailText = ev.detail || ev.status || "";

      return `
        <div style="border-bottom:1px solid #1e293b; padding:4px 0; word-break:break-all;">
          <span style="color:#64748b;">[${timeStr}]</span>
          <span style="color:#a78bfa; font-weight:600;">[${escapeHtml(pkgShort)}]</span>
          <span style="color:${statusColor}; font-weight:bold;">${escapeHtml(statusText)}</span>
          <div style="color:#cbd5e1; margin-top:1px; font-size:10px;">${escapeHtml(detailText)}</div>
        </div>
      `;
    }).join("");
  } catch (err) {
    console.error("Failed to render debug log", err);
  }
}

async function loadCategories() {
  try {
    cachedCategories = await FinanceBridge.getCategories();
  } catch (e) {
    console.error(e);
  }
}

// Diagnostics & Dashboard Balances
async function refreshDiagnostics() {
  try {
    const diag = await FinanceBridge.getDiagnostics();
    const permStatus = await FinanceBridge.checkPermission();

    const isAuthorized = permStatus.enabled || diag.permissionGranted;
    const isLive = permStatus.serviceConnected || diag.listenerConnected;
    const warningCard = document.getElementById("permissionWarningCard");
    const lastCap = document.getElementById("lastCaptureLabel");

    if (warningCard) {
      warningCard.style.display = isAuthorized ? "none" : "flex";
    }

    // Auto-wake listener if permissions granted but service got disconnected by Android OEM
    if (isAuthorized && !isLive) {
      FinanceBridge.rebindService();
    }

    // Header badge
    const headerBadge = document.getElementById("headerListenerBadge");
    if (headerBadge) {
      if (isLive) {
        headerBadge.textContent = "🟢 Live";
        headerBadge.className = "badge badge-encrypted";
      } else if (isAuthorized) {
        headerBadge.textContent = "⚠️ Dormant";
        headerBadge.className = "badge badge-warning";
      } else {
        headerBadge.textContent = "🔴 Disabled";
        headerBadge.className = "badge badge-offline";
      }
    }

    // Settings indicators
    const permSub = document.getElementById("settingsPermSub");
    if (permSub) {
      permSub.textContent = isAuthorized ? "Granted (Android Notification Access)" : "Not Granted (Tap Settings to enable)";
    }

    const listenerSub = document.getElementById("settingsListenerSub");
    if (listenerSub) {
      listenerSub.textContent = isLive ? "Active & Listening (Background connected)" : (isAuthorized ? "Dormant (Tap Reconnect to wake)" : "Inactive (Requires access)");
    }

    if (lastCap) {
      lastCap.textContent = diag.lastCapturedTime ? "Last: " + new Date(diag.lastCapturedTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : "No captures yet";
    }

    // SMS Permission Check
    try {
      const smsPerm = await FinanceBridge.checkSmsPermission();
      const badgeSms = document.getElementById("badgeSmsStatus");
      if (badgeSms) {
        if (smsPerm.allGranted) {
          badgeSms.textContent = "🟢 Active";
          badgeSms.style.background = "#064e3b";
          badgeSms.style.color = "#6ee7b7";
        } else {
          badgeSms.textContent = "⚠️ Permission Needed";
          badgeSms.style.background = "#78350f";
          badgeSms.style.color = "#fcd34d";
        }
      }

      const settingsSmsSub = document.getElementById("settingsSmsSub");
      if (settingsSmsSub) {
        settingsSmsSub.textContent = smsPerm.allGranted 
          ? "Active (Receives SMS broadcasts directly from OS)" 
          : "Not Granted (Tap Permissions to enable direct SMS capture)";
      }
    } catch (_smsErr) {}

    // Double-Entry Balance Calculations
    const netWorthPaise = diag.netWorthPaise || 0;
    const bankBalancePaise = diag.bankBalancePaise || 0;
    const receivablesPaise = diag.outstandingReceivablesPaise || 0;
    const liabilitiesPaise = diag.outstandingLiabilitiesPaise || 0;

    document.getElementById("dashNetWorth").textContent = formatPaise(netWorthPaise);
    document.getElementById("dashBankBalance").textContent = formatPaise(bankBalancePaise);
    document.getElementById("dashReceivables").textContent = formatPaise(receivablesPaise);
    document.getElementById("dashLiabilities").textContent = formatPaise(liabilitiesPaise);

    document.getElementById("dashTotalDebit").textContent = formatPaise(diag.totalDebitPaise || 0);
    document.getElementById("dashTotalCredit").textContent = formatPaise(diag.totalCreditPaise || 0);

    // Tab counts
    const metricCount = document.getElementById("metricCount");
    if (metricCount) metricCount.textContent = diag.totalCapturedCount || 0;
  } catch (e) {
    console.error("Failed to load diagnostics", e);
  }
}

// Smart Clarification System
async function refreshClarifications() {
  const sec = document.getElementById("smartClarificationSection");
  const container = document.getElementById("clarificationCardsContainer");
  const countBadge = document.getElementById("clarificationCountBadge");

  try {
    const list = await FinanceBridge.getPendingClarifications();
    if (!list || list.length === 0) {
      sec.style.display = "none";
      return;
    }

    sec.style.display = "flex";
    countBadge.textContent = list.length;

    container.innerHTML = list.map(c => {
      let options = [];
      try { options = JSON.parse(c.options); } catch (e) { options = ["Personal expense", "I lent money", "Other"]; }

      return `
        <div class="clarification-card">
          <div class="clar-question">${escapeHtml(c.question)}</div>
          <div class="clar-options-grid">
            ${options.map(opt => `
              <button class="clar-opt-btn" onclick="submitClarification('${c.id}', '${escapeHtml(opt)}')">
                ${escapeHtml(opt)}
              </button>
            `).join("")}
          </div>
        </div>
      `;
    }).join("");
  } catch (e) {
    console.error(e);
  }
}

window.submitClarification = async function(clarId, answer) {
  try {
    await FinanceBridge.resolveClarification(clarId, answer);
    showToast(`Clarification recorded: "${answer}"`);
    await refreshAll();
  } catch (e) {
    alert("Error: " + e);
  }
};

// Transactions List & Filters
async function refreshTransactions() {
  const fullContainer = document.getElementById("fullTransactionsList");
  const dashContainer = document.getElementById("dashRecentTransactions");

  try {
    const txns = await FinanceBridge.getTransactions(100);
    const searchVal = (document.getElementById("txSearchInput")?.value || "").toLowerCase().trim();

    const filtered = txns.filter(t => {
      if (currentTxFilter !== "ALL" && t.direction !== currentTxFilter) return false;
      if (searchVal) {
        const text = `${t.counterpartyName} ${t.category} ${t.referenceId} ${t.formattedRupees}`.toLowerCase();
        if (!text.includes(searchVal)) return false;
      }
      return true;
    });

    // Render Recent (Dashboard)
    if (dashContainer) {
      if (txns.length === 0) {
        dashContainer.innerHTML = `<div class="empty-state"><p>No transactions yet.</p></div>`;
      } else {
        dashContainer.innerHTML = txns.slice(0, 5).map(t => renderTransactionCard(t)).join("");
      }
    }

    // Render Full List (Transactions Tab)
    if (fullContainer) {
      if (filtered.length === 0) {
        fullContainer.innerHTML = `<div class="empty-state"><p>No matching transactions found.</p></div>`;
      } else {
        fullContainer.innerHTML = filtered.map(t => renderTransactionCard(t)).join("");
      }
    }
  } catch (e) {
    console.error(e);
  }
}

const CATEGORY_ICONS = {
  "Food and beverages": "🍔",
  "Coffee and restaurants": "☕",
  "Groceries": "🛒",
  "Transportation": "🚗",
  "Shopping": "🛍️",
  "Education": "🎓",
  "Entertainment": "🍿",
  "Subscriptions": "📱",
  "Utilities": "💡",
  "Medical expenses": "💊",
  "Rent": "🏠",
  "Personal transfers": "👥",
  "Lending": "🤝",
  "Borrowing": "📥",
  "Repayments": "↩️",
  "Gifts": "🎁",
  "Investments": "📈",
  "Other": "🏷️"
};

function getCategoryIcon(cat) {
  return CATEGORY_ICONS[cat] || "🏷️";
}

function getPartyAvatar(name) {
  if (!name || name === "Unknown Party") return { initials: "?", bg: "#334155" };
  const parts = name.trim().split(/\s+/);
  const initials = parts.length > 1 ? (parts[0][0] + parts[1][0]).toUpperCase() : parts[0].slice(0, 2).toUpperCase();
  const colors = [
    "linear-gradient(135deg, #3b82f6, #1d4ed8)",
    "linear-gradient(135deg, #8b5cf6, #6d28d9)",
    "linear-gradient(135deg, #ec4899, #be185d)",
    "linear-gradient(135deg, #f59e0b, #d97706)",
    "linear-gradient(135deg, #10b981, #047857)",
    "linear-gradient(135deg, #06b6d4, #0e7490)",
    "linear-gradient(135deg, #6366f1, #4338ca)"
  ];
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
  const bg = colors[Math.abs(hash) % colors.length];
  return { initials, bg };
}

function renderTransactionCard(t) {
  const isDebit = t.direction === 'DEBIT';
  const isCredit = t.direction === 'CREDIT';
  const dirClass = isDebit ? 'debit' : (isCredit ? 'credit' : 'transfer');
  const dirSign = isDebit ? '-' : (isCredit ? '+' : '⇄');
  const sourceName = getAppDisplayName(t.sourcePackage);
  
  const d = new Date(t.timestamp);
  const dateStr = d.toLocaleDateString([], { month: 'short', day: 'numeric' });
  const timeStr = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  const catName = t.category || "Unclassified";
  const catIcon = getCategoryIcon(catName);
  const avatar = getPartyAvatar(t.counterpartyName);

  const confScore = Math.round((t.confidenceScore || 0) * 100);
  let confBadgeClass = "conf-high";
  let confLabel = `${confScore}% Auto`;

  if (confScore < 65 || t.clarificationState === "PENDING") {
    confBadgeClass = "conf-low";
    confLabel = "❓ Needs Clarification";
  } else if (confScore < 90) {
    confBadgeClass = "conf-tentative";
    confLabel = `${confScore}% Tentative`;
  }

  return `
    <div class="transaction-card">
      <div class="txn-top-row">
        <div class="txn-left">
          <div class="txn-avatar" style="background:${avatar.bg};">${escapeHtml(avatar.initials)}</div>
          <div>
            <div class="txn-party">${escapeHtml(t.counterpartyName || 'Unknown Party')}</div>
            <div class="txn-app-tag">${sourceName} ${t.counterpartyIdentifier ? `• ${t.counterpartyIdentifier}` : ''}</div>
          </div>
        </div>
        <div class="txn-amount-box">
          <div class="txn-amount ${dirClass}">${dirSign}${t.formattedRupees}</div>
          <div class="txn-time-tag">${dateStr}, ${timeStr}</div>
        </div>
      </div>
      <div class="txn-classification-row">
        <span class="category-tag-pill" onclick="openCategoryModal('${t.id}', '${escapeHtml(t.counterpartyName || 'Transaction')}', '${escapeHtml(catName)}')">
          <span class="cat-pill-icon">${catIcon}</span>
          <span class="cat-pill-name">${escapeHtml(catName)}</span>
          <span class="cat-pill-edit">✏️</span>
        </span>
        <span class="confidence-tag ${confBadgeClass}">${confLabel}</span>
      </div>
      ${t.referenceId ? `
      <div class="txn-meta-row">
        <span>Ref: <b style="color:#cbd5e1; font-weight:500;">${escapeHtml(t.referenceId)}</b></span>
        <span class="txn-badge ${t.status === 'SUCCESS' ? 'status-success' : 'status-failed'}">${t.status}</span>
      </div>
      ` : ''}
    </div>
  `;
}

function setupFilterPills() {
  document.querySelectorAll(".pill-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".pill-btn").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      currentTxFilter = btn.getAttribute("data-filter");
      refreshTransactions();
    });
  });
}

function setupSearch() {
  const search = document.getElementById("txSearchInput");
  if (search) {
    search.addEventListener("input", () => refreshTransactions());
  }
}

// Debt Management
window.switchDebtFilter = function(type) {
  currentDebtFilter = type;
  document.getElementById("btnDebtTabLent").classList.toggle("active", type === "LENT");
  document.getElementById("btnDebtTabBorrowed").classList.toggle("active", type === "BORROWED");
  document.getElementById("debtSummaryHeader").textContent = type === "LENT" ? "Active Receivables (Money Lent)" : "Active Liabilities (Money Borrowed)";
  refreshDebts();
};

async function refreshDebts() {
  const container = document.getElementById("debtsContainer");
  if (!container) return;

  try {
    const debts = await FinanceBridge.getDebts(currentDebtFilter);
    if (!debts || debts.length === 0) {
      container.innerHTML = `
        <div class="empty-state">
          <p>No ${currentDebtFilter === 'LENT' ? 'receivables' : 'liabilities'} recorded.</p>
        </div>
      `;
      return;
    }

    container.innerHTML = debts.map(d => {
      const pct = Math.min(100, Math.round(((d.principalPaise - d.balancePaise) / d.principalPaise) * 100));
      return `
        <div class="debt-card">
          <div class="debt-top">
            <div>
              <div class="debt-name">${escapeHtml(d.contactName)}</div>
              <div style="font-size:11px; color:var(--text-muted);">${d.status === 'SETTLED' ? '✅ Settled' : '⏳ Active'} • Principal: ${d.formattedPrincipal}</div>
            </div>
            <div style="text-align:right;">
              <div class="debt-amount-highlight ${d.type === 'LENT' ? 'credit' : 'debit'}">${d.formattedBalance}</div>
              <div style="font-size:10px; color:var(--text-muted);">Remaining</div>
            </div>
          </div>
          <div class="debt-progress-bar">
            <div class="debt-progress-fill" style="width:${pct}%"></div>
          </div>
          ${d.status === 'ACTIVE' ? `
            <div style="display:flex; justify-content:flex-end; gap:6px; margin-top:4px;">
              <button class="btn-primary-small" onclick="openRepayModal('${d.id}', '${escapeHtml(d.contactName)}', ${d.balancePaise / 100})">Record Repayment</button>
            </div>
          ` : ''}
        </div>
      `;
    }).join("");
  } catch (e) {
    console.error(e);
  }
}

window.openManualDebtModal = function() {
  document.getElementById("debtModal").style.display = "flex";
};

window.closeDebtModal = function() {
  document.getElementById("debtModal").style.display = "none";
};

async function saveManualDebt() {
  const contact = document.getElementById("inputDebtContact").value.trim();
  const type = document.getElementById("inputDebtType").value;
  const amtStr = document.getElementById("inputDebtAmount").value.trim();
  const notes = document.getElementById("inputDebtNotes").value.trim();

  if (!contact || !amtStr) {
    alert("Please enter contact name and amount");
    return;
  }
  const amountPaise = Math.round(parseFloat(amtStr) * 100);

  try {
    await FinanceBridge.createManualDebt(contact, type, amountPaise, notes);
    closeDebtModal();
    showToast("Debt record created & posted to ledger!");
    await refreshAll();
  } catch (e) {
    alert("Error: " + e);
  }
}

window.openRepayModal = function(debtId, contactName, remainingRs) {
  activeRepayDebtId = debtId;
  document.getElementById("repayContactLabel").textContent = `Contact: ${contactName} (Remaining: ₹${remainingRs.toFixed(2)})`;
  document.getElementById("inputRepayAmount").value = remainingRs;
  document.getElementById("repayModal").style.display = "flex";
};

window.closeRepayModal = function() {
  document.getElementById("repayModal").style.display = "none";
  activeRepayDebtId = null;
};

async function saveDebtRepayment() {
  if (!activeRepayDebtId) return;
  const amtStr = document.getElementById("inputRepayAmount").value.trim();
  if (!amtStr) return;
  const repaymentPaise = Math.round(parseFloat(amtStr) * 100);

  try {
    await FinanceBridge.recordDebtRepayment(activeRepayDebtId, repaymentPaise, "In-app repayment");
    closeRepayModal();
    showToast("Repayment recorded & balanced in ledger!");
    await refreshAll();
  } catch (e) {
    alert("Error: " + e);
  }
}

// Analytics Rendering (Chart.js)
async function renderAnalytics() {
  try {
    const summary = await FinanceBridge.getAnalyticsSummary();
    const diag = await FinanceBridge.getDiagnostics();

    // 1. Category Chart
    const ctxCat = document.getElementById("categoryChart")?.getContext("2d");
    if (ctxCat) {
      if (categoryChartInstance) categoryChartInstance.destroy();

      const labels = (summary.categories || []).map(c => c.category);
      const data = (summary.categories || []).map(c => c.amountRupees);

      categoryChartInstance = new Chart(ctxCat, {
        type: "doughnut",
        data: {
          labels: labels.length ? labels : ["No data"],
          datasets: [{
            data: data.length ? data : [1],
            backgroundColor: ["#38bdf8", "#10b981", "#f59e0b", "#f43f5e", "#818cf8", "#c084fc", "#64748b"]
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { position: "right", labels: { color: "#94a3b8", font: { size: 11 } } }
          }
        }
      });
    }

    // 2. Inflow vs Outflow Chart
    const ctxFlow = document.getElementById("cashflowChart")?.getContext("2d");
    if (ctxFlow) {
      if (cashflowChartInstance) cashflowChartInstance.destroy();

      cashflowChartInstance = new Chart(ctxFlow, {
        type: "bar",
        data: {
          labels: ["Inflow", "Outflow"],
          datasets: [{
            label: "₹ Amount",
            data: [(diag.totalCreditPaise || 0) / 100.0, (diag.totalDebitPaise || 0) / 100.0],
            backgroundColor: ["#10b981", "#f43f5e"]
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: { legend: { display: false } },
          scales: {
            x: { ticks: { color: "#94a3b8" } },
            y: { ticks: { color: "#94a3b8" } }
          }
        }
      });
    }

    // 3. Top Merchants
    const merchContainer = document.getElementById("topMerchantsList");
    if (merchContainer) {
      merchContainer.innerHTML = (summary.merchants || []).map(m => `
        <div class="merch-item">
          <span style="font-weight:600;">${escapeHtml(m.merchant)}</span>
          <span style="font-weight:700; color:var(--accent-debit);">₹${m.amountRupees.toFixed(2)}</span>
        </div>
      `).join("");
    }
  } catch (e) {
    console.error("Failed to render analytics", e);
  }
}

// Weekly Report & jsPDF
async function generateWeeklyDigest() {
  try {
    const report = await FinanceBridge.generateWeeklyReport();
    latestReportData = report;
    displayReport(report);
    showToast("Weekly report generated!");
  } catch (e) {
    alert("Error: " + e);
  }
}

async function loadLatestReport() {
  try {
    const report = await FinanceBridge.getLatestWeeklyReport();
    if (report) {
      latestReportData = report;
      displayReport(report);
    }
  } catch (e) {
    console.error(e);
  }
}

function displayReport(r) {
  const container = document.getElementById("reportDisplayContainer");
  const textBox = document.getElementById("reportContentText");
  if (!container || !textBox) return;

  const start = new Date(r.periodStart).toLocaleDateString();
  const end = new Date(r.periodEnd).toLocaleDateString();

  let text = `=========================================\n`;
  text += `      RASHT WEEKLY FINANCIAL DIGEST       \n`;
  text += `  Period: ${start} - ${end}              \n`;
  text += `=========================================\n\n`;
  text += `Total Outflow: ₹${(r.totalExpensePaise / 100).toFixed(2)}\n`;
  text += `Total Inflow:  ₹${(r.totalIncomePaise / 100).toFixed(2)}\n`;
  text += `Transferred:   ₹${(r.totalTransferredPaise / 100).toFixed(2)}\n\n`;
  text += `--- BALANCE SHEET & NET WORTH ---\n`;
  text += `Liquid Bank:   ₹${(r.bankBalancePaise / 100).toFixed(2)}\n`;
  text += `Receivables:   ₹${(r.outstandingReceivablesPaise / 100).toFixed(2)}\n`;
  text += `Liabilities:   ₹${(r.outstandingLiabilitiesPaise / 100).toFixed(2)}\n`;
  text += `NET WORTH:     ₹${(r.netWorthPaise / 100).toFixed(2)}\n\n`;
  text += `Pending Clarifications: ${r.pendingClarifications}\n\n`;
  text += `--- CATEGORY EXPENDITURE ---\n`;
  (r.categories || []).forEach(c => {
    text += `• ${c.category}: ₹${c.amountRupees.toFixed(2)}\n`;
  });

  textBox.textContent = text;
  container.style.display = "block";
}

function exportReportPdf() {
  if (!latestReportData) return;
  try {
    const { jsPDF } = window.jspdf;
    const doc = new jsPDF();

    doc.setFont("helvetica", "bold");
    doc.setFontSize(18);
    doc.text("Rasht - Weekly Financial Report", 14, 20);

    doc.setFontSize(10);
    doc.setFont("helvetica", "normal");
    doc.text(`Generated on: ${new Date().toLocaleString()}`, 14, 28);
    doc.text(`Period: ${new Date(latestReportData.periodStart).toLocaleDateString()} to ${new Date(latestReportData.periodEnd).toLocaleDateString()}`, 14, 34);

    doc.line(14, 38, 196, 38);

    doc.setFontSize(12);
    doc.setFont("helvetica", "bold");
    doc.text("Summary Highlights", 14, 46);

    doc.setFont("helvetica", "normal");
    doc.setFontSize(10);
    doc.text(`Total Expenditure: Rs. ${(latestReportData.totalExpensePaise / 100).toFixed(2)}`, 14, 54);
    doc.text(`Total Income: Rs. ${(latestReportData.totalIncomePaise / 100).toFixed(2)}`, 14, 60);
    doc.text(`Outstanding Receivables (Lent): Rs. ${(latestReportData.outstandingReceivablesPaise / 100).toFixed(2)}`, 14, 66);
    doc.text(`Outstanding Liabilities (Borrowed): Rs. ${(latestReportData.outstandingLiabilitiesPaise / 100).toFixed(2)}`, 14, 72);
    doc.text(`Calculated Net Worth: Rs. ${(latestReportData.netWorthPaise / 100).toFixed(2)}`, 14, 78);

    let y = 90;
    doc.setFont("helvetica", "bold");
    doc.setFontSize(12);
    doc.text("Category Breakdown", 14, y);

    doc.setFont("helvetica", "normal");
    doc.setFontSize(10);
    y += 8;
    (latestReportData.categories || []).forEach(c => {
      doc.text(`- ${c.category}: Rs. ${c.amountRupees.toFixed(2)}`, 14, y);
      y += 6;
    });

    doc.save("Rasht-Weekly-Report.pdf");
    showToast("PDF Report exported successfully!");
  } catch (e) {
    alert("PDF Export Error: " + e);
  }
}

// Data Export
async function exportLedgerJson() {
  try {
    const data = await FinanceBridge.exportLedgerJson();
    const blob = new Blob([data], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `rasht-backup-${Date.now()}.json`;
    a.click();
    showToast("Decrypted ledger JSON exported!");
  } catch (e) {
    alert("Export Error: " + e);
  }
}

// Category Picker Modal
window.openCategoryModal = function(txnId, partyName, currentCat) {
  selectedTransactionForEdit = txnId;
  const modal = document.getElementById("categoryModal");
  const partyLabel = document.getElementById("modalPartyName");
  const grid = document.getElementById("categoryOptionsGrid");

  partyLabel.textContent = `Counterparty: ${partyName}`;
  grid.innerHTML = cachedCategories.map(cat => {
    const isSelected = cat.toLowerCase() === currentCat.toLowerCase();
    const icon = getCategoryIcon(cat);
    return `
      <button class="cat-btn" style="${isSelected ? 'background:linear-gradient(135deg, #2563eb, #1d4ed8); color:#fff; border-color:#60a5fa;' : ''}" onclick="selectCategory('${escapeHtml(cat)}')">
        <span style="font-size:14px; margin-right:4px;">${icon}</span> ${escapeHtml(cat)}
      </button>
    `;
  }).join("");

  modal.style.display = "flex";
};

window.selectCategory = async function(newCategory) {
  if (!selectedTransactionForEdit) return;
  const remember = document.getElementById("chkRememberRule").checked;

  try {
    await FinanceBridge.updateCategory(selectedTransactionForEdit, newCategory, remember);
    closeCategoryModal();
    showToast(remember ? `Saved & Learned: Always classify as "${newCategory}"` : `Category updated`);
    await refreshAll();
  } catch (err) {
    alert("Update Error: " + err);
  }
};

window.closeCategoryModal = function() {
  const modal = document.getElementById("categoryModal");
  if (modal) modal.style.display = "none";
  selectedTransactionForEdit = null;
};

// Simulator Triggers
function setupSimulatorButtons() {
  const tests = [
    { id: "testPhonePeDebit", pkg: "com.phonepe.app", title: "PhonePe", text: "Payment of ₹1,499.00 to Swiggy was successful. UTR: 432198765432" },
    { id: "testGPayDebit", pkg: "com.google.android.apps.nbu.paisa.user", title: "Google Pay", text: "Paid ₹180.00 to Chai Point" },
    { id: "testPhonePeCredit", pkg: "com.phonepe.app", title: "PhonePe", text: "Received ₹2,000 from Priya Patel. Txn ID: T260811094500" },
    { id: "testBankSmsDebit", pkg: "com.google.android.apps.messaging", title: "HDFC Bank", text: "A/c *4589 debited for Rs 3,200.00 on 08-NOV-26 by UPI:ref 432109876543 to Croma" },
    { id: "testSbiYonoDebit", pkg: "com.google.android.apps.messaging", title: "JX-SBYONO-S", text: "Your Ac Xx0283 debited Rs.270.00 for transfer to Tharun Ac Xx0626 dt 09.11.26 Ref 631318958144. If not done by you, call 1800111109. YONO SBI" },
    { id: "testIppbDebit", pkg: "com.google.android.apps.messaging", title: "JX-IPBMSG-S", text: "A/C X0626 Debit Rs.200.00 for UPI to tharun r on 10-11-26 Ref 636012535058. Avl Bal Rs.231.47. If not you? SMS FREEZE \"full a/c\" to 7669034700-IPPB" }
  ];

  tests.forEach(test => {
    const btn = document.getElementById(test.id);
    if (btn) {
      btn.addEventListener("click", async () => {
        try {
          const res = await FinanceBridge.simulateNotification(test.pkg, test.title, test.text);
          if (res && res.isDuplicate) {
            showToast("Duplicate prevented: " + (res.duplicateEvidence || "Already recorded"));
          }
          await refreshAll();
        } catch (err) {
          alert("Simulation Error: " + err);
        }
      });
    }
  });

  const btnDup = document.getElementById("testDuplicateTrigger");
  if (btnDup) {
    btnDup.addEventListener("click", async () => {
      try {
        const res = await FinanceBridge.simulateNotification(
          "com.phonepe.app", "PhonePe", "Payment of ₹1,499.00 to Swiggy was successful. UTR: 432198765432"
        );
        if (res && res.isDuplicate) {
          showToast("Duplicate Prevented! Evidence: " + res.duplicateEvidence);
        } else {
          showToast("Recorded initial Swiggy payment. Tap again to test duplicate interception!");
        }
        await refreshAll();
      } catch (err) {
        alert("Error: " + err);
      }
    });
  }
}

function showToast(msg) {
  const existing = document.getElementById("appToast");
  if (existing) existing.remove();

  const toast = document.createElement("div");
  toast.id = "appToast";
  toast.textContent = msg;
  toast.style.cssText = "position:fixed; bottom:74px; left:50%; transform:translateX(-50%); background:#f59e0b; color:#0f172a; padding:10px 18px; border-radius:9999px; font-weight:700; font-size:12px; z-index:99999; box-shadow:0 4px 12px rgba(0,0,0,0.5);";
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 3500);
}

function formatPaise(paise) {
  return "₹" + (paise / 100).toFixed(2);
}

async function performSmsScan() {
  const perm = await FinanceBridge.checkSmsPermission();
  if (!perm.readSms) {
    await FinanceBridge.requestSmsPermission();
    showToast("Please allow SMS permission in Android prompt, then tap Scan again!");
    return;
  }

  showToast("Scanning inbox for bank SMS...");
  try {
    const res = await FinanceBridge.syncInboxSms(30);
    if (res.error) {
      alert("SMS Scan Note: " + res.error);
    } else {
      showToast(`Examined ${res.totalExamined} SMS (${res.financialFound} bank): ${res.newSaved} new saved, ${res.duplicates} duplicates.`);
    }
    await refreshAll();
  } catch (err) {
    alert("Scan failed: " + err);
  }
}

function getAppDisplayName(pkg) {
  if (pkg.includes("nbu.paisa")) return "Google Pay";
  if (pkg.includes("phonepe")) return "PhonePe";
  if (pkg.includes("paytm")) return "Paytm";
  if (pkg.includes("npci.upiapp")) return "BHIM";
  if (pkg.includes("sms") || pkg.includes("messaging") || pkg.includes("mms")) return "Bank SMS";
  return "UPI / Bank";
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  return div.innerHTML;
}
