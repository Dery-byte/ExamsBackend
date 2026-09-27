package com.exam.config;

import com.exam.service.SystemSettingService;
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
 * page in the UI also blocks the marks API (/api/marks/**) for that role.
 * The Super Admin is never blocked.
 */
@Configuration
public class MarksSheetAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Autowired
    private SystemSettingService systemSettingService;

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
        if (allowed) return true;

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"The Marks Sheet has been disabled for your role by the Super Admin.\"}");
        return false;
    }

    private boolean isOn(String key) {
        return systemSettingService.getBooleanSetting(key, true);
    }
}
