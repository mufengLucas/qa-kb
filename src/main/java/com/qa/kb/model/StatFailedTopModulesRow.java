package com.qa.kb.model;

public record StatFailedTopModulesRow(
    String module,
    long failCount) {}