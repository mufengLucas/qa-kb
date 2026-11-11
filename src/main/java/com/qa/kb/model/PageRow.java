package com.qa.kb.model;

import java.time.LocalDateTime;

public record PageRow(
    Long id,
    Long spaceId,
    Long parentId,
    String title,
    String slug,
    String contentMd,
    String status,
    Long authorId,
    Integer sort,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt) {}
