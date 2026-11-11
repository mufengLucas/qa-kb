package com.qa.kb.model;

import java.time.LocalDateTime;

public record RevisionRow(
    Long id, Long pageId, String title, String contentMd, Long editorId, LocalDateTime createdAt) {}
