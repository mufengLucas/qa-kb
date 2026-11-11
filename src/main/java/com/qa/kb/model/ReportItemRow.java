package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 周报清单条目：一行轻量待办。
 *
 * <p>{@code task_id} 非空表示该条目关联了项目任务——勾选/取消勾选时会同步任务状态
 * （done / doing）。{@code project_id} 为冗余存储，便于列表直接展示归属项目。
 */
public record ReportItemRow(
    Long id,
    Long reportId,
    String title,
    boolean done,
    Long taskId,
    Long projectId,
    Integer sortOrder,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
