package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 用例执行记录（P2 Phase 4）：一次执行一条。
 *
 * @param executorLogin 来自 JOIN users 的冗余展示列；成员被删除后为 {@code null}
 */
public record TestCaseRunRow(
    long id,
    long caseId,
    String result,
    String note,
    long executorId,
    String executorLogin,
    String executorName,
    LocalDateTime executedAt) {}
