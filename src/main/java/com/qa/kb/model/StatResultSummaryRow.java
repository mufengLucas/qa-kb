package com.qa.kb.model;

public record StatResultSummaryRow(
    long passCount,
    long failCount,
    long blockedCount,
    long skippedCount,
    long totalCount,
    double passRate) {}