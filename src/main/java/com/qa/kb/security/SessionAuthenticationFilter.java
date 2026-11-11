package com.qa.kb.security;

import com.qa.kb.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Translates the opaque session cookie into a Spring Security authentication.
 *
 * <p>Sessions live in MySQL and can be revoked server-side, so logout and password changes take
 * effect immediately. The filter never rejects a request itself; authorization rules do.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  public static final String COOKIE_NAME = "qa_kb_session";

  private final AuthService authService;

  public SessionAuthenticationFilter(AuthService authService) {
    this.authService = authService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      String token = readToken(request);
      authService
          .authenticate(token)
          .ifPresent(
              user -> {
                var authentication =
                    new UsernamePasswordAuthenticationToken(
                        user,
                        token,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
              });
    }
    chain.doFilter(request, response);
  }

  public static String readToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (COOKIE_NAME.equals(cookie.getName())) {
        String value = cookie.getValue();
        return value == null || value.isBlank() ? null : value;
      }
    }
    return null;
  }
}
