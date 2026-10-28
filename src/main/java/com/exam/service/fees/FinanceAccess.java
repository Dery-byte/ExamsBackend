package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.FinanceDuty;
import org.springframework.security.access.AccessDeniedException;

/**
 * Who may do what with fees. The Super Admin can do everything. An accountant is a bursar (sets fees,
 * fee switches and results holds; voids payments) or a cashier (records payments, prints receipts,
 * reads the fees and payments).
 */
public final class FinanceAccess {

    private FinanceAccess() {}

    public static boolean isFinance(User u) {
        return u != null && (u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.ACCOUNTANT);
    }

    public static boolean isBursar(User u) {
        if (u == null) return false;
        return u.getRole() == Role.SUPER_ADMIN || (u.getRole() == Role.ACCOUNTANT && u.getFinanceDuty() == FinanceDuty.BURSAR);
    }

    public static void requireFinance(User u) {
        if (!isFinance(u)) throw new AccessDeniedException("Only the accounts office can do this.");
    }

    public static void requireBursar(User u) {
        if (!isBursar(u)) throw new AccessDeniedException("Only the bursar can do this. Ask the bursar or the Super Admin.");
    }
}
