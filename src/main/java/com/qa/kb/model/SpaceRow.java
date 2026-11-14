package com.qa.kb.model;

/**
 * 空间行。
 *
 * @param restricted 是否受限（P2 Phase 3）：true 时仅空间成员与全局管理员可见，非成员读写一律 403；
 *     取值用 {@code Boolean.TRUE.equals(row.restricted())} 做空值安全判断
 */
public record SpaceRow(
    Long id,
    String name,
    String slug,
    String icon,
    String description,
    Integer sort,
    Boolean restricted) {}
