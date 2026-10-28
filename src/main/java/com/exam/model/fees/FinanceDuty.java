package com.exam.model.fees;

/**
 * What an accountant may do. BURSAR: sets fees and prices, fee switches and results holds, voids
 * payments (and, later, gives discounts). CASHIER: records payments, prints receipts, issues bills.
 */
public enum FinanceDuty {
    BURSAR, CASHIER;

    public String label() { return this == BURSAR ? "Bursar" : "Cashier"; }
}
