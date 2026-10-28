package com.exam.service.fees;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.fees.FinanceDuty;
import com.exam.repository.UserRepository;
import com.exam.service.admin.AccountService;
import com.exam.service.admin.CredentialEmailService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The Super Admin's accounts-office staff: accountant logins (bursar or cashier). A new account gets a
 * temporary password that must be changed at first sign-in, shown once and emailed when there is an
 * address. Deactivating one uses the normal account switch.
 */
@Service
public class FinanceStaffService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CredentialEmailService credentialEmails;

    public record StaffRequest(String firstname, String lastname, String username, String email, String phone, String duty, Boolean emailLogin) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return users.findByRole(Role.ACCOUNTANT).stream()
                .sorted(Comparator.comparing((User u) -> u.getFinanceDuty() == FinanceDuty.BURSAR ? 0 : 1)
                        .thenComparing(u -> CurrentUserService.displayName(u).toLowerCase()))
                .map(FinanceStaffService::dto).toList();
    }

    @Transactional
    public Map<String, Object> create(StaffRequest req) {
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        String first = need(req.firstname(), "First name", 60), last = need(req.lastname(), "Last name", 60);
        String username = need(req.username(), "Staff ID", 60);
        String email = need(req.email(), "Email", 120).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("Email '" + email + "' is not valid.");
        if (users.findByUsername(username).isPresent()) throw new IllegalArgumentException("Staff ID " + username + " already has an account.");
        if (users.findByEmail(email).isPresent()) throw new IllegalArgumentException("Email " + email + " is already registered.");
        String phone = clean(req.phone(), 30);
        if (phone != null && users.findByPhone(phone) != null) throw new IllegalArgumentException("Phone number " + phone + " is already registered.");

        String password = tempPassword();
        User u = new User();
        u.setFirstname(first);
        u.setLastname(last);
        u.setUsername(username);
        u.setEmail(email);
        u.setPhone(phone);
        u.setRole(Role.ACCOUNTANT);
        u.setFinanceDuty(duty(req.duty()));
        u.setEnabled(true);
        u.setMustChangePassword(Boolean.TRUE);
        u.setPassword(passwordEncoder.encode(password));
        u = users.save(u);
        Map<String, Object> out = dto(u);
        out.put("password", password);
        out.put("emailed", !Boolean.FALSE.equals(req.emailLogin()) && sendLogin(u, password));
        return out;
    }

    @Transactional
    public Map<String, Object> update(Long id, StaffRequest req) {
        User u = accountant(id);
        if (req == null) throw new IllegalArgumentException("Nothing to save.");
        if (req.firstname() != null) u.setFirstname(need(req.firstname(), "First name", 60));
        if (req.lastname() != null) u.setLastname(need(req.lastname(), "Last name", 60));
        if (req.phone() != null) u.setPhone(clean(req.phone(), 30));
        if (req.duty() != null) u.setFinanceDuty(duty(req.duty()));
        return dto(users.save(u));
    }

    /** A new temporary password (changed at next sign-in), shown once and emailed. */
    @Transactional
    public Map<String, Object> resetPassword(Long id) {
        User u = accountant(id);
        String password = tempPassword();
        u.setPassword(passwordEncoder.encode(password));
        u.setMustChangePassword(Boolean.TRUE);
        users.save(u);
        Map<String, Object> out = dto(u);
        out.put("password", password);
        out.put("emailed", sendLogin(u, password));
        return out;
    }

    private boolean sendLogin(User u, String password) {
        Map<String, String> c = new HashMap<>();
        c.put("firstname", u.getFirstname());
        c.put("username", u.getUsername());
        c.put("email", u.getEmail());
        c.put("password", password);
        return credentialEmails.queue(List.of(c), u.getFinanceDuty() == FinanceDuty.BURSAR ? "bursar" : "cashier") > 0;
    }

    private User accountant(Long id) {
        return users.findById(id).filter(u -> u.getRole() == Role.ACCOUNTANT)
                .orElseThrow(() -> new IllegalArgumentException("That accountant no longer exists."));
    }

    private static Map<String, Object> dto(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("firstname", u.getFirstname());
        m.put("lastname", u.getLastname());
        m.put("name", CurrentUserService.displayName(u));
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("duty", (u.getFinanceDuty() == null ? FinanceDuty.CASHIER : u.getFinanceDuty()).name());
        m.put("photo", u.getProfilePic());
        m.putAll(AccountService.status(u));
        return m;
    }

    private static FinanceDuty duty(String raw) {
        if (raw == null || raw.isBlank()) return FinanceDuty.CASHIER;
        try { return FinanceDuty.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose bursar or cashier."); }
    }

    private static String need(String s, String what, int max) {
        String v = clean(s, max);
        if (v == null) throw new IllegalArgumentException(what + " is required.");
        return v;
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (v.length() > max) throw new IllegalArgumentException("'" + v.substring(0, 20) + "…' is too long.");
        return v;
    }

    private static String tempPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        return sb.toString();
    }
}
