package com.exam.config;

import com.exam.service.SystemSettingService;
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
 * The developer's Course Materials switch, enforced on the server: while it is off, every request to
 * /api/materials (including signed download links) is refused with 403 MATERIALS_OFF for every role.
 * Uploaded files stay on disk and come back when the module is switched on again.
 */
@Configuration
public class MaterialsModuleInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Autowired private SystemSettingService settings;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/materials/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || settings.materialsOn()) return true;
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"MATERIALS_OFF\",\"message\":\"Course notes and assignments are switched off for this institution.\"}");
        return false;
    }
}
