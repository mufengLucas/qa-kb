package com.qa.kb.auth;

import java.util.Locale;

/** Team roles. Permissions are additive from VIEWER to ADMIN. */
public enum Role {
  VIEWER,
  MEMBER,
  ADMIN;

  public boolean canWrite() {
    return this == MEMBER || this == ADMIN;
  }

  public boolean canAdmin() {
    return this == ADMIN;
  }

  /** Parses a stored/requested role, rejecting unknown values. */
  public static Role parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("role is required");
    }
    try {
      return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("unknown role: " + value);
    }
  }
}
