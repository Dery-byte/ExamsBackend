package com.exam.config;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.service.monitoring.DeveloperAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A developer keeps access only while their email is in the developer_email table: deleting the row
 * ends an already signed-in session on its next request.
 */
@Configuration
public class DeveloperAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Autowired
    private DeveloperAuthService developerAuthService;

    @Autowired
    private com.exam.service.comms.CurrentUserService currentUserService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/developer/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        String email = currentUserService.current()
                .filter(u -> u.getRole() == Role.DEVELOPER)
                .map(User::getEmail).orElse(null);
        if (email != null && developerAuthService.isDeveloper(email)) return true;

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Your developer access has been removed.\"}");
        return false;
    }
}
