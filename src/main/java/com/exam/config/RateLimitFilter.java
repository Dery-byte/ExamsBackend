package com.exam.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Per-IP limits on the public endpoints attackers target: sign-in, sign-up and password reset.
 * (Per-account lockout after repeated wrong passwords is in AuthenticationController.)
 * Added to the security chain in SecurityConfiguration, not registered as a servlet filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Limit(String method, String pathSuffix, int max, long windowMs, String bucket) {}

    private static final long MIN = 60_000L;
    private static final List<Limit> LIMITS = List.of(
            new Limit("POST", "/api/v1/auth/authenticate",          30, 10 * MIN, "login"),
            new Limit("POST", "/api/v1/auth/register",              10, 60 * MIN, "register"),
            new Limit("POST", "/api/v1/auth/register/super-admin",   5, 60 * MIN, "register-sa"),
            new Limit("POST", "/api/v1/auth/forgotten-password",    10, 15 * MIN, "reset"),
            new Limit("POST", "/api/v1/auth/reset-password",        10, 15 * MIN, "reset"),
            new Limit("POST", "/api/v1/auth/reset-password-with-token", 10, 15 * MIN, "reset"),
            new Limit("GET",  "/api/v1/auth/validate-reset-token",  30, 15 * MIN, "reset-check"),
            new Limit("GET",  "/api/v1/auth/verify",                60, 15 * MIN, "verify"),
            new Limit("POST", "/api/v1/auth/developer/request-code",  5, 15 * MIN, "dev-code"),
            new Limit("POST", "/api/v1/auth/developer/verify",       15, 15 * MIN, "dev-verify")
    );

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        for (Limit l : LIMITS) {
            boolean matches = l.method().equalsIgnoreCase(request.getMethod())
                    && (path.equals(l.pathSuffix()) || ((l.bucket().equals("reset") || l.bucket().equals("verify")) && path.startsWith(l.pathSuffix() + "/")));
            if (!matches) continue;
            String key = "ip:" + l.bucket() + ":" + clientIp(request);
            if (!limiter.tryAcquire(key, l.max(), l.windowMs())) {
                long wait = limiter.retryAfterSeconds(key, l.windowMs());
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(wait));
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Too many attempts. Please wait " + Math.max(1, wait / 60)
                        + " minute(s) and try again.\"}");
                return;
            }
            break;
        }
        chain.doFilter(request, response);
    }

    /** First X-Forwarded-For hop when behind the hosting proxy, else the socket address. */
    public static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
    }
}
