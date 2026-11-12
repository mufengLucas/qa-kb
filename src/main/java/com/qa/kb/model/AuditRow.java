package com.qa.kb.model;

import java.time.LocalDateTime;

/**
 * 操作审计行：谁在何时对什么做了什么。
 *
 * <p>user_id 刻意不设外键（成员删除后审计必须保留），因此 userId 可为 null（匿名/已删用户）；
 * userLogin/userName 由查询端 LEFT JOIN users 补齐，成员已删时为 null。
 */
public record AuditRow(
    Long id,
    Long userId,
    String action,
    String targetType,
    String targetId,
    String detail,
    LocalDateTime createdAt,
    String userLogin,
    String userName) {}
