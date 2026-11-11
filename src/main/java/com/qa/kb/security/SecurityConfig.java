package com.qa.kb.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.auth.AuthService;
import com.qa.kb.common.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Stateless, cookie-session security.
 *
 * <ul>
 *   <li>Authentication: opaque session cookie validated against SQLite on every request.
 *   <li>CSRF: double-submit cookie ({@code XSRF-TOKEN} / {@code X-XSRF-TOKEN}) for all mutations.
 *       Login is exempt so a first-time browser can sign in without a prior token.
 *   <li>Authorization: everyone authenticated can read; MEMBER/ADMIN can write; ADMIN manages users.
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, AuthService authService, ObjectMapper objectMapper) throws Exception {
    CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieName("XSRF-TOKEN");
    csrf.setHeaderName("X-XSRF-TOKEN");
    csrf.setCookiePath("/");

    // Spring Security 6 defaults to XOR-masking CSRF tokens for BREACH protection, which makes the
    // raw XSRF-TOKEN cookie value unacceptable as the header value. This SPA reads the cookie and
    // echoes it in X-XSRF-TOKEN (the documented double-submit contract), so resolve tokens as-is.
    // There is no server-rendered HTML, so there is no BREACH surface to mask against.
    CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

    http.csrf(
            config ->
                config
                    .csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(csrfRequestHandler)
                    // Our filter re-establishes the SecurityContext from the session cookie on
                    // every request, which SessionManagementFilter treats as a fresh
                    // authentication. The default CsrfAuthenticationStrategy then deletes the
                    // XSRF-TOKEN cookie after every authenticated mutation, so the SPA's next
                    // write fails with 403. Login already issues a new server-side session token,
                    // so there is no fixation window for CSRF rotation to close here.
                    .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                    .ignoringRequestMatchers("/api/auth/login"))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/styles.css",
                        "/assets/**",
                        "/vendor/**",
                        "/favicon.ico",
                        "/favicon.svg",
                        "/api/health",
                        "/api/auth/login",
                        "/api/auth/csrf")
                    .permitAll()
                    .requestMatchers("/api/users/**", "/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .authenticated()
                    .requestMatchers("/api/**")
                    .hasAnyRole("ADMIN", "MEMBER")
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, ex) ->
                            write(
                                response,
                                objectMapper,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "unauthorized",
                                "请先登录"))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            write(
                                response,
                                objectMapper,
                                HttpServletResponse.SC_FORBIDDEN,
                                "forbidden",
                                "没有权限执行该操作")))
        .addFilterBefore(
            new SessionAuthenticationFilter(authService), UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  private static void write(
      HttpServletResponse response,
      ObjectMapper objectMapper,
      int status,
      String code,
      String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), ApiError.of(code, message));
  }
}
