package com.exam.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open session in view, as Spring Boot did it (spring.jpa.open-in-view is now false), but NOT for
 * the file download routes.
 *
 * With open-in-view, a request keeps its database connection until the response has been fully
 * sent. A file download sends for as long as the student's network takes (minutes on a weak phone
 * signal), so 20 students downloading notes at once held all 20 pool connections and every other
 * request (sign-in, quizzes) failed with "Connection is not available, request timed out after
 * 30000ms". These routes do their database work in their own @Transactional service calls and
 * return only a file path, so they don't need the session to stay open.
 */
@Configuration
public class OpenInViewConfig implements WebMvcConfigurer {

    private final EntityManagerFactory emf;

    public OpenInViewConfig(EntityManagerFactory emf) {
        this.emf = emf;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        OpenEntityManagerInViewInterceptor osiv = new OpenEntityManagerInViewInterceptor();
        osiv.setEntityManagerFactory(emf);
        registry.addWebRequestInterceptor(osiv)
                .excludePathPatterns("/api/materials/download/**", "/api/materials/preview/**", "/api/materials/zip/**")
                .order(Ordered.HIGHEST_PRECEDENCE);   // open before the other interceptors, as before
    }
}
