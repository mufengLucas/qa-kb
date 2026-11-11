package com.qa.kb.model;

import java.time.LocalDateTime;

public record SessionRow(
    String id, Long userId, LocalDateTime createdAt, LocalDateTime lastSeen, LocalDateTime expiresAt) {}
