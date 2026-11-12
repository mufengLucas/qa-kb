package com.qa.kb.model;

import java.time.LocalDateTime;

/** 页面附件元数据；文件本体以 UUID 存于 kb.upload-dir，行内只记原始文件名与存储名。 */
public record AttachmentRow(
    Long id,
    Long pageId,
    String filename,
    String storedName,
    Long size,
    String mime,
    Long uploaderId,
    LocalDateTime createdAt) {}
