package com.exam.service.comms;

import com.exam.model.User;
import com.exam.model.comms.AuditLog;
import com.exam.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes and searches the audit trail. Recording never throws. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User actor, String action, String method, String path, String entityId,
                       String details, Integer status, String ip) {
        try {
            AuditLog a = new AuditLog();
            if (actor != null) {
                a.setActorId(actor.getId());
                a.setActorName(CurrentUserService.displayName(actor));
                a.setActorRole(actor.getRole() != null ? actor.getRole().name() : null);
            }
            a.setAction(action);
            a.setHttpMethod(method);
            a.setPath(truncate(path, 500));
            a.setEntityId(entityId);
            a.setDetails(truncate(details, 2000));
            a.setStatusCode(status);
            a.setIpAddress(ip);
            auditLogRepository.save(a);
        } catch (Exception e) {
            log.warn("Could not write audit entry '{}': {}", action, e.getMessage());
        }
    }

    public Map<String, Object> search(String actor, String action, String role, LocalDate from, LocalDate to,
                                      int page, int size) {
        Page<AuditLog> result = auditLogRepository.search(
                blankToNull(actor), blankToNull(action), blankToNull(role),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", result.getContent());
        m.put("page", result.getNumber());
        m.put("size", result.getSize());
        m.put("totalItems", result.getTotalElements());
        m.put("totalPages", result.getTotalPages());
        return m;
    }

    public List<String> actions() {
        return auditLogRepository.findDistinctActions();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
