package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 用例列表/详情视图：用例行 + 创建人展示列 + 执行统计（P2 Phase 4）。
 *
 * <p>扁平结构是为了让 MyBatis 按列名自动映射构造器参数；统计列全部是相关子查询，
 * 单页查询即可拿到，无需额外 round-trip。
 *
 * @param lastResult 最近一次执行结果，从未执行过为 {@code null}
 */
public record TestCaseView(
    long id,
    String module,
    String title,
    String precondition,
    String steps,
    String expected,
    String priority,
    String status,
    long creatorId,
    String creatorLogin,
    String creatorName,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    int runCount,
    int passCount,
    int failCount,
    String lastResult,
    LocalDateTime lastRunAt) {

  /** 还原成行记录（更新/校验路径使用）。 */
  public TestCaseRow toRow() {
    return new TestCaseRow(
        id,
        module,
        title,
        precondition,
        steps,
        expected,
        priority,
        status,
        creatorId,
        createdAt,
        updatedAt);
  }
}
