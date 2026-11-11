package com.qa.kb.web;

import com.qa.kb.auth.Role;
import com.qa.kb.security.SessionUser;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authenticated principals for standalone MockMvc tests. */
final class TestAuth {

  static final Authentication ADMIN = of(1L, "admin", Role.ADMIN);
  static final Authentication MEMBER = of(2L, "alice", Role.MEMBER);
  static final Authentication VIEWER = of(3L, "guest", Role.VIEWER);

  private TestAuth() {}

  static Authentication of(long id, String login, Role role) {
    return new UsernamePasswordAuthenticationToken(
        new SessionUser(id, login, "成员" + id, role),
        "token",
        List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
  }
}
