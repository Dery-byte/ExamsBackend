package com.exam.config;

import com.exam.model.User;
import com.exam.model.features.Feature;
import com.exam.service.SystemSettingService;
import com.exam.service.comms.CurrentUserService;
import com.exam.service.features.FeatureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the Super Admin's per-role Marks Sheet switches on the server, so hiding the
 * page in the UI also blocks the marks API (/api/marks/**) for that role. Students are also
 * blocked when their department has switched off report cards (Feature.STUDENT_REPORT_CARD).
 * The Super Admin is never blocked.
 */
@Configuration
public class MarksSheetAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private FeatureService featureService;

    @Autowired
    private CurrentUserService currentUserService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/marks/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());

        if (roles.contains("SUPER_ADMIN")) return true;

        boolean allowed =
                (roles.contains("ADMIN")    && isOn(SystemSettingService.MARKS_SHEET_VISIBLE_ADMIN)) ||
                (roles.contains("LECTURER") && isOn(SystemSettingService.MARKS_SHEET_VISIBLE_LECTURER)) ||
                (roles.contains("NORMAL")   && isOn(SystemSettingService.MARKS_SHEET_VISIBLE_STUDENT));
        if (!allowed) return deny(response, "The Marks Sheet has been disabled for your role by the Super Admin.");

        // Everything a student reaches under /api/marks is their report cards, which their department can switch off
        if (roles.contains("NORMAL") && !roles.contains("ADMIN") && !roles.contains("LECTURER")) {
            User student = currentUserService.current().orElse(null);
            if (student != null && !featureService.isOnFor(Feature.STUDENT_REPORT_CARD, student))
                return deny(response, Feature.STUDENT_REPORT_CARD.label() + " have been turned off by "
                        + (featureService.isOnSystemWide(Feature.STUDENT_REPORT_CARD) ? "your department" : "the Super Admin") + ".");
        }
        return true;
    }

    private static boolean deny(HttpServletResponse response, String message) throws java.io.IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        return false;
    }

    private boolean isOn(String key) {
        return systemSettingService.getBooleanSetting(key, true);
    }
}
