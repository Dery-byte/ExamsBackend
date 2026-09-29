package com.exam.config;

import com.exam.service.monitoring.ErrorMonitorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sends every API request that fails on the server to the error monitor: exceptions nobody handled
 * (they arrive here as {@code ex}) and 5xx answers controllers built themselves.
 */
@Configuration
public class ErrorCaptureInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Autowired
    private ErrorMonitorService errorMonitor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        int status = response.getStatus();
        if (ex != null) errorMonitor.recordRequest(request, 500, ex);
        else if (status >= 500) errorMonitor.recordRequest(request, status, null);
    }
}
