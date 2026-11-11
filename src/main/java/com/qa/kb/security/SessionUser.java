package com.qa.kb.security;

import com.qa.kb.auth.Role;

/** Authenticated principal stored in the SecurityContext. */
public record SessionUser(long id, String login, String name, Role role) {}
