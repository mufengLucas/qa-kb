package com.qa.kb.model;

import java.time.LocalDate;

public record ProjectWithProgress(
    Long id,
    String name,
    String projectKey,
    Long ownerId,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    String description,
    Long totalTasks,
    Long doneTasks) {}
