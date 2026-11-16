package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 用例行（P2 Phase 4）。
 *
 * <p>独立模块：只用 {@code module} 做归属与筛选，不关联空间/项目。执行统计不落在本行，
 * 由 {@link TestCaseView} 在查询端用子查询聚合。
 */
public record TestCaseRow(
    long id,
    String module,
    String title,
    String precondition,
    String steps,
    String expected,
    String priority,
    String status,
    long creatorId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
