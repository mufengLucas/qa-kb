package com.qa.kb.model;

import java.time.LocalDateTime;

public record TestCaseCommentRow(
    long id,
    long caseId,
    long authorId,
    String authorLogin,
    String authorName,
    String body,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}