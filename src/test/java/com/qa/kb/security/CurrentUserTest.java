package com.qa.kb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("CurrentUser")
class CurrentUserTest {

  @Test
  void returnsTheSessionUserPrincipal() {
    SessionUser user = new SessionUser(1, "alice", "Alice", Role.MEMBER);
    var authentication = new UsernamePasswordAuthenticationToken(user, "t", List.of());

    assertThat(CurrentUser.require(authentication)).isEqualTo(user);
  }

  @Test
  void rejectsNullAuthentication() {
    assertThatThrownBy(() -> CurrentUser.require(null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void rejectsAnonymousAuthentication() {
    var anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    assertThatThrownBy(() -> CurrentUser.require(anonymous))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsNonSessionPrincipals() {
    var authentication = new UsernamePasswordAuthenticationToken("someone", null, List.of());

    assertThatThrownBy(() -> CurrentUser.require(authentication))
        .isInstanceOf(ApiException.class);
  }
}
