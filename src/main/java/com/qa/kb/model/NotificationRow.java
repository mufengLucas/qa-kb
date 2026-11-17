package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 站内通知行（P2 Phase 5）。
 *
 * <p>{@code dedupKey} 是幂等键（与 user_id 组成唯一键）：周报提醒为 {@code weekly:<周一>}，
 * 任务逾期为 {@code overdue:<taskId>:<dueDate>}，每日扫描重复执行不会重复生成。
 *
 * <p>{@code refType}/{@code refId} 指向触发来源（week / task），前端点击可跳转对应页面。
 */
public record NotificationRow(
    long id,
    long userId,
    String type,
    String title,
    String body,
    String refType,
    long refId,
    String dedupKey,
    LocalDateTime readAt,
    LocalDateTime createdAt) {}
