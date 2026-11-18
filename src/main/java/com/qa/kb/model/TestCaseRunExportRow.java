package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 执行记录导出行（A1）：用例归属 + 执行结果的扁平视图。
 *
 * <p>导出要「一行一条执行、带上用例模块与标题」，与 {@link TestCaseRunRow}（详情页按用例查询）
 * 的形状不同，故单独建 record；列名走 MyBatis 下划线转驼峰自动映射。
 */
public record TestCaseRunExportRow(
    String module,
    String title,
    String result,
    String note,
    String executorLogin,
    String executorName,
    LocalDateTime executedAt) {}
