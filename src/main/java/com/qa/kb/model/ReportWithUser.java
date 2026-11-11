package com.qa.kb.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReportWithUser(
    Long id,
    Long userId,
    String userName,
    LocalDate reportDate,
    String done,
    String plan,
    String blockers,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
