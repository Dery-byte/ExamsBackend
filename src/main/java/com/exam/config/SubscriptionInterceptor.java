package com.exam.config;

import com.exam.model.User;
import com.exam.service.subscription.SubscriptionService;
import com.exam.service.subscription.SubscriptionService.Access;
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
 * Applies the subscription to every signed-in request ({@link SubscriptionService#accessFor}):
 * read-only lets GET through and refuses changes; blocked refuses everything but the few calls the
 * app shell needs. Both answer 402 with a code the frontend acts on. Developers, public endpoints and
 * students in the middle of an exam are never stopped.
 */
@Configuration
public class SubscriptionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String READ_ONLY = "SUBSCRIPTION_READ_ONLY";
    public static final String SUSPENDED = "SUBSCRIPTION_SUSPENDED";

    private static final String A = "/api/v1/auth";
    /** Needed to show the blocked page, check whether a payment went through, sign out, or change a forced password. */
    private static final Set<String> ALWAYS = Set.of(
            A + "/current-user", A + "/logout", A + "/updatepassword", A + "/feature-flags", A + "/public-settings",
            A + "/institution", A + "/institution/logo", A + "/client-errors", A + "/authenticate", A + "/subscription/notice", A + "/subscription/check-payment");

    @Autowired
    private SubscriptionService subscription;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/v1/developer/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User u)) return true;

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (ALWAYS.contains(path)) return true;

        Access access = subscription.accessFor(u);
        if (access == Access.FULL) return true;
        boolean read = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        if (access == Access.READ_ONLY && read) return true;

        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(access == Access.READ_ONLY
                ? "{\"code\":\"" + READ_ONLY + "\",\"message\":\"The portal is read-only at the moment: you can view and export, but changes can't be saved.\"}"
                : "{\"code\":\"" + SUSPENDED + "\",\"message\":\"The portal is unavailable at the moment. Please contact your institution's administrator.\"}");
        return false;
    }
}
