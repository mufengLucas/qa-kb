package com.qa.kb.model;

/**
 * 空间成员（P2 Phase 3）：(space_id, user_id, role) 唯一。
 *
 * <p>login/name 来自 JOIN users 的冗余展示列，不落库。
 */
public record SpaceMemberRow(
    long spaceId, long userId, String login, String name, String role) {}
