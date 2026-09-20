package com.lawrencenno.commonbeacon.identity;

import com.lawrencenno.commonbeacon.shared.ApiProblems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", Map.of(
                "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(user -> User.withUsername(user.getEmail()).password(user.passwordHash())
                        .roles(user.getRole().name()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean LoginRateLimiter loginRateLimiter() {
        return new LoginRateLimiter(Clock.systemUTC(), 10, 10_000, Duration.ofMinutes(1));
    }
    @Bean SecurityFilterChain security(HttpSecurity http, IdentityService identity,
            ApiProblems problems, ObjectMapper mapper, LoginRateLimiter limiter) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/articles", "/api/v1/articles/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/questions/*/replies", "/api/v1/replies/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/boards/*/questions", "/api/v1/questions/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/boards", "/api/v1/boards/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/boards").hasRole("ADMINISTRATOR")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/boards/*").hasRole("ADMINISTRATOR")
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMINISTRATOR")
                .requestMatchers("/api/v1/moderation/**").hasAnyRole("MODERATOR", "ADMINISTRATOR")
                .anyRequest().authenticated());
        http.requestCache(cache -> cache.disable());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                        problems.write(response, 401, "UNAUTHENTICATED", "Please sign in to continue."))
                .accessDeniedHandler((request, response, error) -> problems.write(response, 403,
                        error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                        error instanceof CsrfException ? "Your security token expired. Try again."
                                : "You do not have permission for this action.")));
        // Defaults retain session-backed, BREACH-protected CSRF tokens.
        http.formLogin(form -> form.loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .successHandler((request, response, authentication) -> {
                    response.setContentType("application/json");
                    response.setHeader("Cache-Control", "no-store");
                    mapper.writeValue(response.getWriter(), identity.current(authentication));
                })
                .failureHandler((request, response, error) ->
                        problems.write(response, 401, "INVALID_CREDENTIALS", "Email or password is incorrect.")));
        http.logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                if ("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(request.getServletPath())) {
                    if (!limiter.allow(request.getRemoteAddr())) {
                        response.setHeader("Retry-After", "60");
                        problems.write(response, 429, "RATE_LIMITED", "Too many sign-in attempts. Wait a minute and try again.");
                        return;
                    }
                    var email = request.getParameter("email");
                    var password = request.getParameter("password");
                    if (email == null || email.length() > 254 || password == null || password.length() > 128) {
                        problems.write(response, 401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
