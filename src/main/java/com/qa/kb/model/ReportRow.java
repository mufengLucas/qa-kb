package com.qa.kb.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReportRow(
    Long id,
    Long userId,
    LocalDate reportDate,
    String done,
    String plan,
    String blockers,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
