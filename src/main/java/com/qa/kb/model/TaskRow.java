package com.qa.kb.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TaskRow(
    Long id,
    Long projectId,
    Long milestoneId,
    String title,
    Long assigneeId,
    String status,
    Integer priority,
    LocalDate startDate,
    LocalDate dueDate,
    Integer progress,
    LocalDateTime updatedAt) {}
