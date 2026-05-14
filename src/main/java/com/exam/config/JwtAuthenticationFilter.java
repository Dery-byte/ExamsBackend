////package com.exam.config;
////
////import com.exam.repository.TokenRepository;
////import com.exam.service.JwtService;
////import jakarta.servlet.FilterChain;
////import jakarta.servlet.ServletException;
////import jakarta.servlet.http.HttpServletRequest;
////import jakarta.servlet.http.HttpServletResponse;
////import java.io.IOException;
////
////import lombok.RequiredArgsConstructor;
////import org.springframework.beans.factory.annotation.Autowired;
////import org.springframework.lang.NonNull;
////import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
////import org.springframework.security.core.context.SecurityContextHolder;
////import org.springframework.security.core.userdetails.UserDetails;
////import org.springframework.security.core.userdetails.UserDetailsService;
////import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
////import org.springframework.stereotype.Component;
////import org.springframework.web.filter.OncePerRequestFilter;
////
////@Component
////@RequiredArgsConstructor
////public class JwtAuthenticationFilter extends OncePerRequestFilter {
////@Autowired
////  private final JwtService jwtService;
////  @Autowired
////  private final UserDetailsService userDetailsService;
////  @Autowired
////  private final TokenRepository tokenRepository;
////
////  @Override
////  protected void doFilterInternal(
////      @NonNull HttpServletRequest request,
////      @NonNull HttpServletResponse response,
////      @NonNull FilterChain filterChain
////  ) throws ServletException, IOException {
////    final String authHeader = request.getHeader("Authorization");
////    final String jwt;
////    final String userEmail;
////    if (authHeader == null ||!authHeader.startsWith("Bearer ")) {
////      filterChain.doFilter(request, response);
////      return;
////    }
////    jwt = authHeader.substring(7);
////    userEmail = jwtService.extractUsername(jwt);
////    if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
////      UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
////      var isTokenValid = tokenRepository.findByToken(jwt)
////          .map(t -> !t.isExpired() && !t.isRevoked())
////          .orElse(false);
////      if (jwtService.isTokenValid(jwt, userDetails) && isTokenValid) {
////        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
////            userDetails,
////            null,
////            userDetails.getAuthorities()
////        );
////        authToken.setDetails(
////            new WebAuthenticationDetailsSource().buildDetails(request)
////        );
////        SecurityContextHolder.getContext().setAuthentication(authToken);
////      }
////    }
////    filterChain.doFilter(request, response);
////  }
////}
//
//
//package com.exam.config;
//
//import com.exam.repository.TokenRepository;
//import com.exam.service.JwtService;
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.Cookie;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import java.io.IOException;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.lang.NonNull;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import static org.aspectj.weaver.tools.cache.SimpleCacheFactory.path;
//
//@Component
//@RequiredArgsConstructor
//public class JwtAuthenticationFilter extends OncePerRequestFilter {
//    @Autowired
//    private final JwtService jwtService;
//    @Autowired
//    private final UserDetailsService userDetailsService;
//    @Autowired
//    private final TokenRepository tokenRepository;
//
//    @Override
//    protected void doFilterInternal(
//            @NonNull HttpServletRequest request,
//            @NonNull HttpServletResponse response,
//            @NonNull FilterChain filterChain
//    ) throws ServletException, IOException {
//
//        String jwt = null;
//
//        // FIRST: Try to get token from cookie
//        if (request.getCookies() != null) {
//            for (Cookie cookie : request.getCookies()) {
//                if ("accessToken".equals(cookie.getName())) {
//                    jwt = cookie.getValue();
//                    break;
//                }
//            }
//        }
//        String path = request.getRequestURI();
//        if (path.equals("/token-info")) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//        // FALLBACK: Check Authorization header (for backward compatibility)
//        if (jwt == null) {
//            final String authHeader = request.getHeader("Authorization");
//            if (authHeader != null && authHeader.startsWith("Bearer ")) {
//                jwt = authHeader.substring(7);
//            }
//        }
//
//        // If no token found anywhere, continue without authentication
//        if (jwt == null) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//
//        // Extract username from token
//        final String userEmail = jwtService.extractUsername(jwt);
//
//        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
//            UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
//
//            // Check if token is valid in database
//            var isTokenValid = tokenRepository.findByToken(jwt)
//                    .map(t -> !t.isExpired() && !t.isRevoked())
//                    .orElse(false);
//
//            if (jwtService.isTokenValid(jwt, userDetails) && isTokenValid) {
//                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
//                        userDetails,
//                        null,
//                        userDetails.getAuthorities()
//                );
//                authToken.setDetails(
//                        new WebAuthenticationDetailsSource().buildDetails(request)
//                );
//                SecurityContextHolder.getContext().setAuthentication(authToken);
//            }
//        }
//        filterChain.doFilter(request, response);
//    }
//}








package com.exam.config;

import com.exam.repository.TokenRepository;
import com.exam.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenRepository tokenRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        /* 1️⃣ Never process CORS preflight */
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        /* 2️⃣ Skip public endpoints */
        String requestPath = request.getRequestURI();
        if (requestPath.equals("/token-info")
                || requestPath.startsWith("/auth")
                || requestPath.startsWith("/public")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = null;

        /* 3️⃣ Read JWT from cookie */
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("accessToken".equals(cookie.getName())) {
                    jwt = cookie.getValue();
                    break;
                }
            }
        }

        /* 4️⃣ Fallback to Authorization header */
        if (jwt == null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                jwt = authHeader.substring(7);
            }
        }

        /* 5️⃣ Validate token structure BEFORE parsing */
        if (jwt == null || jwt.isBlank() || jwt.chars().filter(c -> c == '.').count() != 2) {
            filterChain.doFilter(request, response);
            return;
        }

        String userEmail;
        try {
            userEmail = jwtService.extractUsername(jwt);
        } catch (Exception ex) {
            // malformed / expired / tampered token
            filterChain.doFilter(request, response);
            return;
        }

        /* 6️⃣ Authenticate user */
        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

            boolean isTokenValid = tokenRepository.findByToken(jwt)
                    .map(token -> !token.isExpired() && !token.isRevoked())
                    .orElse(false);

            if (jwtService.isTokenValid(jwt, userDetails) && isTokenValid) {

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
