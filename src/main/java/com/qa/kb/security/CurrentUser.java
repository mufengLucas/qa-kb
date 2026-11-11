package com.qa.kb.security;

import com.qa.kb.common.ApiException;
import org.springframework.security.core.Authentication;

/** Extracts the authenticated teammate from the SecurityContext. */
public final class CurrentUser {

  private CurrentUser() {}

  public static SessionUser require(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionUser user)) {
      throw ApiException.unauthorized("请先登录");
    }
    return user;
  }
}
