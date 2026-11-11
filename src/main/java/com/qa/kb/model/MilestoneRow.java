package com.qa.kb.model;

import java.time.LocalDate;

public record MilestoneRow(
    Long id, Long projectId, String name, LocalDate dueDate, String status) {}
