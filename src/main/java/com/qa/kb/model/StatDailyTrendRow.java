package com.qa.kb.model;

public record StatDailyTrendRow(
    String execDate,
    long execCount,
    long passCount,
    long failCount) {}