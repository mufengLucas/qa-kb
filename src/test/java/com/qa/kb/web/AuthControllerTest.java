package com.qa.kb.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.audit.AuditService;
import com.qa.kb.auth.AuthService;
import com.qa.kb.auth.Role;
import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.security.SessionUser;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

  @Mock private AuthService authService;
  @Mock private AuditService auditService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new AuthController(authService, auditService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CsrfTokenResolver())
            .build();
  }

  @Test
  void loginReturnsUserAndSetsHttpOnlySessionCookie() throws Exception {
    when(authService.login(eq("alice"), eq("Passw0rd!x"), anyString()))
        .thenReturn(
            new AuthService.LoginResult(
                "TOKEN123",
                new SessionUser(2, "alice", "Alice", Role.MEMBER),
                LocalDateTime.of(2026, 11, 5, 22, 0)));
    when(authService.sessionTtl()).thenReturn(Duration.ofHours(12));

    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"alice\",\"password\":\"Passw0rd!x\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.login").value("alice"))
        .andExpect(jsonPath("$.role").value("MEMBER"))
        .andExpect(cookie().value("qa_kb_session", "TOKEN123"))
        .andExpect(cookie().httpOnly("qa_kb_session", true))
        .andExpect(cookie().path("qa_kb_session", "/"));
  }

  @Test
  void loginUsesForwardedClientIp() throws Exception {
    when(authService.login(eq("alice"), eq("Passw0rd!x"), eq("10.0.0.8")))
        .thenReturn(
            new AuthService.LoginResult(
                "T", new SessionUser(2, "alice", "A", Role.MEMBER), LocalDateTime.now()));
    when(authService.sessionTtl()).thenReturn(Duration.ofHours(1));

    mvc.perform(
            post("/api/auth/login")
                .header("X-Forwarded-For", "10.0.0.8, 10.0.0.9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"alice\",\"password\":\"Passw0rd!x\"}"))
        .andExpect(status().isOk());

    verify(authService).login("alice", "Passw0rd!x", "10.0.0.8");
  }

  @Test
  void loginRejectsBlankCredentials() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"\",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));

    verify(authService, never()).login(anyString(), anyString(), anyString());
  }

  @Test
  void logoutDeletesSessionAndClearsCookie() throws Exception {
    mvc.perform(post("/api/auth/logout").cookie(new Cookie("qa_kb_session", "TOKEN123")))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge("qa_kb_session", 0));

    verify(authService).logout("TOKEN123");
  }

  @Test
  void meReturnsThePrincipal() throws Exception {
    mvc.perform(get("/api/auth/me").principal(TestAuth.ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.login").value("admin"))
        .andExpect(jsonPath("$.role").value("ADMIN"));
  }

  @Test
  void meRejectsAnonymousCaller() throws Exception {
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  @Test
  void changePasswordDelegatesWithThePrincipalId() throws Exception {
    mvc.perform(
            post("/api/auth/password")
                .principal(TestAuth.MEMBER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Old0Pass!x\",\"newPassword\":\"New0Pass!x\"}"))
        .andExpect(status().isNoContent());

    verify(authService).changePassword(2L, "Old0Pass!x", "New0Pass!x");
  }

  @Test
  void csrfEndpointReturnsTokenAndHeaderName() throws Exception {
    mvc.perform(get("/api/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("csrf-token"))
        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"));
  }

  private static final class CsrfTokenResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return parameter.getParameterType() == CsrfToken.class;
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
      return new DefaultCsrfToken("X-XSRF-TOKEN", "XSRF-TOKEN", "csrf-token");
    }
  }
}
