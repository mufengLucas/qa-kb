package com.qa.kb.web;

import com.qa.kb.attachment.AttachmentService;
import com.qa.kb.model.AttachmentRow;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 页面附件：上传 / 列表 / 下载 / 删除。
 *
 * <p>权限由 SecurityConfig 统一收口——读需登录，写操作需 ADMIN/MEMBER（与空间/页面同一模型，
 * 不校验归属人）。下载走控制器流式输出，存储目录不在 static 下，不暴露为静态资源。
 */
@RestController
@RequestMapping("/api")
public class AttachmentController {

  private final AttachmentService attachmentService;

  public AttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping("/pages/{pageId}/attachments")
  public ResponseEntity<AttachmentRow> upload(
      @PathVariable long pageId, @RequestParam("file") MultipartFile file,
      Authentication authentication) throws IOException {
    SessionUser user = CurrentUser.require(authentication);
    AttachmentRow created =
        attachmentService.save(
            pageId,
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes(),
            user.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/pages/{pageId}/attachments")
  public List<AttachmentRow> list(@PathVariable long pageId) {
    return attachmentService.list(pageId);
  }

  /** 下载：RFC 5987 编码文件名，中文名在各浏览器均可正确落地。 */
  @GetMapping("/attachments/{id}")
  public ResponseEntity<Resource> download(@PathVariable long id) throws IOException {
    AttachmentService.Download download = attachmentService.open(id);
    AttachmentRow row = download.row();
    MediaType mediaType =
        row.mime() == null || row.mime().isBlank()
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(row.mime());
    String contentDisposition =
        ContentDisposition.builder("attachment")
            .filename(row.filename(), StandardCharsets.UTF_8)
            .build()
            .toString();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
        .contentType(mediaType)
        .contentLength(row.size())
        .body(new FileSystemResource(download.path()));
  }

  @DeleteMapping("/attachments/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    attachmentService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
