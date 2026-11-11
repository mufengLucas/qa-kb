package com.qa.kb.model;

import com.qa.kb.auth.Role;
import java.time.LocalDateTime;

public record UserRow(
    Long id,
    String login,
    String name,
    String passwordHash,
    Role role,
    boolean active,
    LocalDateTime createdAt) {}
