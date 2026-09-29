package com.exam.service.academic;

import com.exam.model.academic.DocumentVerification;
import com.exam.model.features.Feature;
import com.exam.repository.DocumentVerificationRepository;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

/** Issues the codes printed on transcripts and report cards, and answers public look-ups. */
@Service
public class DocumentVerificationService {

    /** No 0/O, 1/I/L, so a code read off paper is unambiguous. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private DocumentVerificationRepository repository;
    @Autowired private FeatureService featureService;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private InstitutionService institutionService;

    public boolean enabled() {
        return featureService.isOnSystemWide(Feature.DOCUMENT_VERIFICATION);
    }

    /** What a PDF needs to print: the code and the page that checks it. Empty when the switch is off. */
    public record Issued(String code, String url) {}

    public Optional<Issued> issue(DocumentVerification.Type type, Long studentId, String studentName,
                                  String username, String program, String summary) {
        if (!enabled()) return Optional.empty();
        DocumentVerification d = new DocumentVerification();
        d.setCode(newCode());
        d.setDocType(type);
        d.setStudentId(studentId);
        d.setStudentName(cut(studentName, 150));
        d.setStudentUsername(cut(username, 80));
        d.setProgramName(cut(program, 200));
        d.setSummary(cut(summary, 600));
        d.setInstitutionName(cut(institutionService.name(), 150));
        d.setIssuedBy(currentUserService.current().map(u -> u.getUsername()).orElse(null));
        repository.save(d);
        return Optional.of(new Issued(d.getCode(), urlFor(d.getCode())));
    }

    public String urlFor(String code) {
        return institutionService.portalUrl() + "/verify/" + code;
    }

    /** Public look-up. Shows only what was printed on the document. */
    public Map<String, Object> verify(String rawCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled()) {
            out.put("status", "DISABLED");
            return out;
        }
        String code = normalise(rawCode);
        Optional<DocumentVerification> found = code == null ? Optional.empty() : repository.findByCode(code);
        if (found.isEmpty()) {
            out.put("status", "NOT_FOUND");
            return out;
        }
        DocumentVerification d = found.get();
        out.put("status", d.isRevoked() ? "REVOKED" : "VALID");
        out.put("code", d.getCode());
        out.put("type", d.getDocType().name());
        out.put("studentName", d.getStudentName());
        out.put("studentId", d.getStudentUsername());
        out.put("program", d.getProgramName());
        out.put("summary", d.getSummary());
        out.put("institution", d.getInstitutionName());
        out.put("issuedAt", d.getIssuedAt().toString());
        if (d.isRevoked()) out.put("revokedReason", d.getRevokedReason());
        return out;
    }

    public List<DocumentVerification> recent(String q) {
        PageRequest page = PageRequest.of(0, 200);
        if (q == null || q.isBlank()) return repository.findAllByOrderByIssuedAtDesc(page);
        String term = q.trim();
        return repository.findByStudentUsernameContainingIgnoreCaseOrCodeContainingIgnoreCaseOrderByIssuedAtDesc(term, term, page);
    }

    public DocumentVerification setRevoked(Long id, boolean revoked, String reason) {
        DocumentVerification d = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Document not found."));
        d.setRevoked(revoked);
        d.setRevokedReason(revoked ? cut(reason, 300) : null);
        return repository.save(d);
    }

    /** "abcd efgh-jk mn" → "ABCD-EFGH-JKMN"; null when it can't be a code. */
    static String normalise(String raw) {
        if (raw == null) return null;
        String s = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (s.length() != 12) return null;
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8);
    }

    private String newCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i == 4 || i == 8) sb.append('-');
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (!repository.existsByCode(sb.toString())) return sb.toString();
        }
        throw new IllegalStateException("Could not create a unique verification code.");
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
