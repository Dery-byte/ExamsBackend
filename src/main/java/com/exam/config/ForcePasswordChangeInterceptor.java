package com.exam.config;

import com.exam.model.User;
import com.exam.model.features.Feature;
import com.exam.service.features.FeatureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Accounts created by staff (bulk import, HOD / lecturer registration, admin resets) start with a
 * password someone else knows. Until the owner picks a new one, only the calls the change-password
 * page needs are allowed; everything else answers 403 with code PASSWORD_CHANGE_REQUIRED.
 * Controlled by the {@link Feature#FORCE_PASSWORD_CHANGE} switch.
 */
@Configuration
public class ForcePasswordChangeInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    private static final String A = "/api/v1/auth";
    private static final Set<String> ALLOWED = Set.of(
            A + "/updatepassword", A + "/current-user", A + "/logout",
            A + "/feature-flags", A + "/public-settings", A + "/authenticate", A + "/institution", A + "/institution/logo", A + "/client-errors");

    @Autowired
    private FeatureService featureService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    /** True when this user must change their password before doing anything else. */
    public boolean mustChange(User u) {
        return u != null && u.requiresPasswordChange() && featureService.isOnSystemWide(Feature.FORCE_PASSWORD_CHANGE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User u) || !mustChange(u)) return true;

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (ALLOWED.contains(path)) return true;

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + CODE + "\",\"message\":\"Please choose a new password before continuing.\"}");
        return false;
    }
}
