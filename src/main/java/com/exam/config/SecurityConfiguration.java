//////package com.exam.config;
//////
//////import lombok.RequiredArgsConstructor;
//////import org.springframework.beans.factory.annotation.Autowired;
//////import org.springframework.context.annotation.Bean;
//////import org.springframework.context.annotation.Configuration;
//////import org.springframework.http.HttpStatus;
//////import org.springframework.security.authentication.AuthenticationProvider;
//////import org.springframework.security.config.Customizer;
//////import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//////import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//////import org.springframework.security.config.http.SessionCreationPolicy;
//////import org.springframework.security.core.context.SecurityContextHolder;
//////import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
//////import org.springframework.security.web.SecurityFilterChain;
//////import org.springframework.security.web.authentication.HttpStatusEntryPoint;
//////import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
//////import org.springframework.security.web.authentication.logout.LogoutHandler;
//////import org.springframework.web.reactive.function.client.WebClient;
//////import org.springframework.web.servlet.config.annotation.CorsRegistry;
//////import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//////
//////@Configuration
//////@EnableWebSecurity
//////@RequiredArgsConstructor
//////public class SecurityConfiguration {
//////
//////  private final JwtAuthenticationFilter jwtAuthFilter;
//////  private final AuthenticationProvider authenticationProvider;
//////  private final LogoutHandler logoutHandler;
//////  private  OAuthAuthenicationSuccessHandler handler;
//////
//////
//////  @Bean
//////  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//////    http
//////            .csrf()
//////            .disable()
//////            .authorizeHttpRequests()
//////            .requestMatchers("/api/v1/auth/**")
//////            .permitAll()
//////            .anyRequest()
//////            .authenticated()
//////            .and()
//////            .sessionManagement()
//////            .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//////            .and()
//////            .authenticationProvider(authenticationProvider)
//////            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
//////            .logout()
//////            .logoutUrl("/api/v1/auth/logout")
//////            .addLogoutHandler(logoutHandler)
//////            .logoutSuccessHandler((request, response, authentication) -> SecurityContextHolder.clearContext());
////////    http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
////////            .oauth2Login(Customizer.withDefaults());
////////    http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
////////            .oauth2Login(Customizer.withDefaults());
////////    http.oauth2Login(oauth -> {
////////      oauth.loginPage("/index");
////////      oauth.successHandler(handler);
////////    });
////////    http.logout(logoutForm -> {
////////      logoutForm.logoutUrl("/api/v1/auth/logout");
////////      logoutForm.logoutSuccessUrl("/login?logout=true");
////////    });
//////
//////
////////    http
////////            .cors(Customizer.withDefaults())
////////            .exceptionHandling(customizer-> customizer.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
////////            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
////////            .authorizeHttpRequests(authorize-> authorize
////////                    .requestMatchers("/", "/auth**/","/public").permitAll()
////////                    .anyRequest().authenticated())
////////            .oauth2ResourceServer(c-> c.opaqueToken(Customizer.withDefaults()));
//////////            .oauth2Login(Customizer.withDefaults());
//////    return http.build();
//////  }
//////
//////
//////
////////
////////
////////  @Bean
////////  public WebMvcConfigurer corsConfigurer() {
////////    return new WebMvcConfigurer() {
////////      @Override
////////      public void addCorsMappings(CorsRegistry registry) {
////////        registry.addMapping("/**")
////////                .allowedOrigins("https://assessmentapp-e1d04.web.app") // Specify your frontend URL
////////                .allowedMethods("GET", "POST", "PUT", "DELETE")
////////                .allowedHeaders("*")
////////                .allowCredentials(true);
////////      }
////////    };
////////
////////
////////  }
//////}
////
////package com.exam.config;
////
////import lombok.RequiredArgsConstructor;
////import org.springframework.context.annotation.Bean;
////import org.springframework.context.annotation.Configuration;
////import org.springframework.security.authentication.AuthenticationProvider;
////import org.springframework.security.config.annotation.web.builders.HttpSecurity;
////import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
////import org.springframework.security.config.http.SessionCreationPolicy;
////import org.springframework.security.core.context.SecurityContextHolder;
////import org.springframework.security.web.SecurityFilterChain;
////import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
////import org.springframework.security.web.authentication.logout.LogoutHandler;
////import org.springframework.web.cors.CorsConfiguration;
////import org.springframework.web.cors.CorsConfigurationSource;
////import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
////
////import java.util.Arrays;
////
////@Configuration
////@EnableWebSecurity
////@RequiredArgsConstructor
////public class SecurityConfiguration {
////
////    private final JwtAuthenticationFilter jwtAuthFilter;
////    private final AuthenticationProvider authenticationProvider;
////    private final LogoutHandler logoutHandler;
////    private OAuthAuthenicationSuccessHandler handler;
////
////    @Bean
////    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
////        http
////                .csrf(csrf -> csrf.disable())
////                .cors(cors -> cors.configurationSource(corsConfigurationSource())) // Add CORS
////                .authorizeHttpRequests(auth -> auth
////                        .requestMatchers("/token-info").permitAll()
////                        .requestMatchers("/api/v1/auth/**").permitAll()
////                        .anyRequest().authenticated()
////                )
////                .sessionManagement(session -> session
////                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
////                )
////                .authenticationProvider(authenticationProvider)
////                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
////                .logout(logout -> logout
////                        .logoutUrl("/api/v1/auth/logout")
////                        .addLogoutHandler(logoutHandler)
////                        .logoutSuccessHandler((request, response, authentication) ->
////                                SecurityContextHolder.clearContext())
////                );
////
////        return http.build();
////    }
////
////    @Bean
////    public CorsConfigurationSource corsConfigurationSource() {
////        CorsConfiguration configuration = new CorsConfiguration();
////        // Allow both local development and production
////        configuration.setAllowedOrigins(Arrays.asList(
////                "http://localhost:4200",  // Local development
////                "https://assessmentapp-e1d04.web.app"  // Production
////        ));
////        configuration.setAllowedMethods(Arrays.asList(
////                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
////        ));
////
////        configuration.setAllowedHeaders(Arrays.asList("*"));
////
////        // CRITICAL: Allow credentials (for cookies)
////        configuration.setAllowCredentials(true);
////
////        // Expose headers if needed
////        configuration.setExposedHeaders(Arrays.asList("Authorization"));
////
////        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
////        source.registerCorsConfiguration("/**", configuration);
////
////        return source;
////    }
////}
//
//
//
//
//
//
//
//
//package com.exam.config;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.http.HttpMethod;
//import org.springframework.security.authentication.AuthenticationProvider;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.web.SecurityFilterChain;
//import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
//import org.springframework.security.web.authentication.logout.LogoutHandler;
//import org.springframework.web.cors.CorsConfiguration;
//import org.springframework.web.cors.CorsConfigurationSource;
//import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
//
//import java.util.List;
//
//@Configuration
//@EnableWebSecurity
//@RequiredArgsConstructor
//public class SecurityConfiguration {
//
//    private final JwtAuthenticationFilter jwtAuthFilter;
//    private final AuthenticationProvider authenticationProvider;
//    private final LogoutHandler logoutHandler;
//
//    @Bean
//    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//
//        http
//                // ❌ Disable CSRF (JWT + cookies handled manually)
//                .csrf(csrf -> csrf.disable())
//
//                // ✅ Enable CORS
//                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
//
//                // ✅ Authorization rules
//                .authorizeHttpRequests(auth -> auth
//                        // Allow preflight requests
//                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
//
//                        // Public endpoints
//
//                                // ✅ Allow authentication endpoints - multiple patterns
//                                .requestMatchers("/api/v1/auth/**").permitAll()
//                                .requestMatchers("/api/v1/auth/authenticate").permitAll()
//                                .requestMatchers("/api/v1/auth/register").permitAll()
//                                .requestMatchers("/api/v1/auth/register/**").permitAll()
//                                .requestMatchers("/token-info").permitAll()
////                        .requestMatchers("/api/v1/auth/**").permitAll()
////                        .requestMatchers("/token-info").permitAll()
////                        .requestMatchers("/current-user").authenticated()  // make sure authenticated users can access
//
//
//                        // Everything else requires auth
//                        .anyRequest().authenticated()
//                )
//
//                // ✅ Stateless session
//                .sessionManagement(session ->
//                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                )
//
//                // ✅ Authentication provider
//                .authenticationProvider(authenticationProvider)
//
//                // ✅ JWT filter
//                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
//
//                // ✅ Logout
//                .logout(logout -> logout
//                        .logoutUrl("/api/v1/auth/logout")
//                        .addLogoutHandler(logoutHandler)
//                        .logoutSuccessHandler((request, response, authentication) ->
//                                SecurityContextHolder.clearContext())
//                );
//
//        return http.build();
//    }
//
//    // =========================
//    // CORS CONFIGURATION
//    // =========================
//    @Bean
//    public CorsConfigurationSource corsConfigurationSource() {
//        CorsConfiguration configuration = new CorsConfiguration();
//
//        // ❗ Must be explicit when using cookies
//        configuration.setAllowedOrigins(List.of(
//                "http://localhost:4200",
//                "https://assessmentapp-e1d04.web.app"
//        ));
//
//        configuration.setAllowedMethods(List.of(
//                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
//        ));
//
//        // ❗ Wildcard NOT allowed with credentials
//        configuration.setAllowedHeaders(List.of(
//                "Authorization",
//                "Content-Type"
//        ));
//
//        // ✅ Expose headers
//        configuration.setExposedHeaders(List.of(
//                "Authorization",
//                "Set-Cookie",
//                "Access-Control-Allow-Origin",
//                "Access-Control-Allow-Credentials"
//        ));
//        configuration.setExposedHeaders(List.of("*"));
//
//        // ✅ Required for cookies
//        configuration.setAllowCredentials(true);
//
//        // ✅ Cache preflight for 1 hour
//        configuration.setMaxAge(3600L);
//
//
//        UrlBasedCorsConfigurationSource source =
//                new UrlBasedCorsConfigurationSource();
//        source.registerCorsConfiguration("/**", configuration);
//        return source;
//    }
//}




package com.exam.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final LogoutHandler logoutHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // ✅ Disable CSRF (not needed for stateless JWT via headers)
                .csrf(csrf -> csrf.disable())

                // ✅ Enable CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ✅ Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Allow preflight requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public endpoints (authentication)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/token-info").permitAll()

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // ✅ Stateless session (no server-side session)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // ✅ Authentication provider
                .authenticationProvider(authenticationProvider)

                // ✅ JWT filter (extracts token from Authorization header)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // ✅ Logout
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler(logoutHandler)
                        .logoutSuccessHandler((request, response, authentication) ->
                                SecurityContextHolder.clearContext())
                );

        return http.build();
    }

    // =========================
    // CORS CONFIGURATION (No Credentials)
    // =========================
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // ✅ Allowed origins (your frontend URLs)
        configuration.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "https://assessmentapp-e1d04.web.app"
        ));

        // ✅ Allowed HTTP methods
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));

        // ✅ Allowed headers (Authorization header is key)
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept"
        ));

        // ✅ Expose Authorization header so frontend can read it
        configuration.setExposedHeaders(List.of(
                "Authorization"
        ));

        // ❌ NO credentials - we're using localStorage/sessionStorage
        configuration.setAllowCredentials(false);

        // ✅ Cache preflight for 1 hour
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}