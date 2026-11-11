package com.qa.kb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.auth.AuthService;
import com.qa.kb.auth.Role;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAuthenticationFilter")
class SessionAuthenticationFilterTest {

  @Mock private AuthService authService;

  private SessionAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new SessionAuthenticationFilter(authService);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validSessionBecomesAnAuthenticationWithRoleAuthority() throws Exception {
    SessionUser user = new SessionUser(1, "admin", "管理员", Role.ADMIN);
    when(authService.authenticate("TOKEN")).thenReturn(Optional.of(user));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, "TOKEN"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isEqualTo(user);
    assertThat(authentication.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_ADMIN");
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void viewerGetsOnlyTheViewerAuthority() throws Exception {
    when(authService.authenticate("TOKEN"))
        .thenReturn(Optional.of(new SessionUser(3, "guest", "访客", Role.VIEWER)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, "TOKEN"));
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_VIEWER");
  }

  @Test
  void unknownSessionLeavesTheContextAnonymous() throws Exception {
    when(authService.authenticate("BAD")).thenReturn(Optional.empty());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, "BAD"));
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void missingCookieDoesNotHitTheDatabase() throws Exception {
    filter.doFilter(
        new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

    verify(authService).authenticate(null);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void blankCookieValueIsTreatedAsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, " "));
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    verify(authService).authenticate(null);
  }

  @Test
  void existingAuthenticationIsNotReplaced() throws Exception {
    Authentication existing =
        new UsernamePasswordAuthenticationToken("principal", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(existing);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, "TOKEN"));
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    verify(authService, never()).authenticate("TOKEN");
  }

  @Test
  void readTokenFindsTheSessionCookie() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie("other", "x"), new Cookie(SessionAuthenticationFilter.COOKIE_NAME, "TOKEN"));

    assertThat(SessionAuthenticationFilter.readToken(request)).isEqualTo("TOKEN");
  }

  @Test
  void readTokenReturnsNullWithoutCookies() {
    assertThat(SessionAuthenticationFilter.readToken(new MockHttpServletRequest())).isNull();
  }

  @Test
  void readTokenReturnsNullForBlankValue() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionAuthenticationFilter.COOKIE_NAME, ""));

    assertThat(SessionAuthenticationFilter.readToken(request)).isNull();
  }
}
