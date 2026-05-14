package com.exam.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;

@Component
public class SameSiteCookieFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Wrap response to modify Set-Cookie headers
        HttpServletResponseWrapper wrapper = new HttpServletResponseWrapper(httpResponse) {
            @Override
            public void addHeader(String name, String value) {
                if ("Set-Cookie".equalsIgnoreCase(name)) {
                    value = addSameSiteAttribute(value);
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                if ("Set-Cookie".equalsIgnoreCase(name)) {
                    value = addSameSiteAttribute(value);
                }
                super.setHeader(name, value);
            }
        };

        chain.doFilter(request, wrapper);
    }

    private String addSameSiteAttribute(String cookie) {
        // Only add if not already present
        if (!cookie.toLowerCase().contains("samesite")) {
            // Ensure Secure is set (required for SameSite=None)
            if (!cookie.toLowerCase().contains("secure")) {
                cookie += "; Secure";
            }
            cookie += "; SameSite=None";
        }
        return cookie;
    }
}