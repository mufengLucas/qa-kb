package com.qa.kb.model;

/** Join row linking a daily report to a project. */
public record ReportProjectLink(Long reportId, Long projectId) {}
