package com.qa.kb.attachment;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.AttachmentRow;
import com.qa.kb.persistence.AttachmentMapper;
import com.qa.kb.persistence.PageMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 页面附件：文件本体落盘 {@code kb.upload-dir}（UUID 存储名，杜绝路径穿越与同名覆盖），
 * 元数据进 {@code attachments} 表（page FK ON DELETE CASCADE，删页面/删空间自动连带）。
 */
public class AttachmentService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

  /** 与 {@code spring.servlet.multipart.max-file-size: 20MB} 对齐，先于落盘拦截。 */
  public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

  /** schema 上限（filename VARCHAR(255) / TEXT），超长截断主干、保留扩展名。 */
  public static final int MAX_FILENAME_LENGTH = 255;

  private static final String DEFAULT_MIME = "application/octet-stream";

  private final AttachmentMapper attachmentMapper;
  private final PageMapper pageMapper;
  private final Path uploadDir;

  public AttachmentService(AttachmentMapper attachmentMapper, PageMapper pageMapper, Path uploadDir) {
    this.attachmentMapper = attachmentMapper;
    this.pageMapper = pageMapper;
    this.uploadDir = uploadDir.toAbsolutePath().normalize();
  }

  /** 页面附件列表；页面不存在或已软删一律 404（与读取端页面语义一致）。 */
  public List<AttachmentRow> list(long pageId) {
    requirePage(pageId);
    return attachmentMapper.listByPage(pageId);
  }

  /**
   * 保存附件：先校验页面与文件，再落盘、写元数据；元数据写入失败时回滚刚落的文件，
   * 不留孤儿。
   *
   * @param originalFilename 浏览器提交的原始文件名（可能带路径分隔符）
   * @param contentType      客户端声明的 MIME，空则回落 octet-stream
   * @param content          文件内容（控制器已按 multipart 上限约束）
   */
  public AttachmentRow save(long pageId, String originalFilename, String contentType, byte[] content, long uploaderId)
      throws IOException {
    requirePage(pageId);
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("附件内容不能为空");
    }
    if (content.length > MAX_FILE_SIZE) {
      throw new IllegalArgumentException("附件不能超过 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB");
    }
    String filename = cleanFilename(originalFilename);
    String mime =
        contentType == null || contentType.isBlank() ? DEFAULT_MIME : contentType.strip();
    String storedName = UUID.randomUUID().toString();
    Path target = uploadDir.resolve(storedName);
    Files.createDirectories(uploadDir);
    try {
      Files.write(target, content);
      attachmentMapper.insert(pageId, filename, storedName, content.length, mime, uploaderId);
      long id = attachmentMapper.lastInsertId();
      return attachmentMapper
          .findById(id)
          .orElseThrow(() -> new IllegalStateException("附件保存后未能读取编号"));
    } catch (RuntimeException | IOException failure) {
      // 元数据失败 → 删掉刚写的文件，避免 upload-dir 累积孤儿
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanup) {
        log.warn("附件元数据写入失败后清理文件也失败: {}", target, cleanup);
      }
      throw failure;
    }
  }

  /** 下载视图：元数据 + 实际路径；文件在磁盘上缺失按 404 处理并告警。 */
  public Download open(long id) {
    AttachmentRow row =
        attachmentMapper
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("附件不存在"));
    Path path = pathOf(row.storedName());
    if (!Files.isRegularFile(path)) {
      log.warn("附件文件缺失: id={} stored={} filename={}", id, row.storedName(), row.filename());
      throw ApiException.notFound("附件文件已丢失，请联系管理员");
    }
    return new Download(row, path);
  }

  /** 删除附件：先删元数据再删文件（页面级联已在 DB 层保证整行消失）。 */
  public void delete(long id) {
    AttachmentRow row =
        attachmentMapper
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("附件不存在"));
    attachmentMapper.delete(id);
    try {
      Files.deleteIfExists(pathOf(row.storedName()));
    } catch (IOException e) {
      // 行已删，磁盘残留只是空间问题：告警即可，不影响请求
      log.warn("附件文件删除失败: {} ({})", row.storedName(), row.filename(), e);
    }
  }

  /** 下载所需的元数据与文件路径。 */
  public record Download(AttachmentRow row, Path path) {}

  /** 删除/下载前权限校验用：取附件所属页面编号（附件不存在 → 404，不触碰磁盘）。 */
  public long pageIdOf(long attachmentId) {
    return attachmentMapper
        .findById(attachmentId)
        .orElseThrow(() -> ApiException.notFound("附件不存在"))
        .pageId();
  }

  private Path pathOf(String storedName) {
    Path path = uploadDir.resolve(storedName).normalize();
    if (!path.getParent().equals(uploadDir)) {
      throw ApiException.badRequest("非法的存储名");
    }
    return path;
  }

  private void requirePage(long pageId) {
    pageMapper
        .findById(pageId)
        .orElseThrow(() -> ApiException.notFound("页面不存在"));
  }

  /**
   * 文件名清洗：剥掉路径（浏览器偶发提交 {@code C:\fakepath\x} 或恶意 {@code ../../x}），
   * 去首尾空白，空值回落「未命名」，超长截断主干保留扩展名。
   */
  static String cleanFilename(String original) {
    if (original == null || original.isBlank()) {
      return "未命名";
    }
    String name = original.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    name = name.strip();
    if (name.isEmpty() || name.equals(".") || name.equals("..")) {
      return "未命名";
    }
    if (name.length() <= MAX_FILENAME_LENGTH) {
      return name;
    }
    int dot = name.lastIndexOf('.');
    String ext = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
    return name.substring(0, MAX_FILENAME_LENGTH - ext.length()) + ext;
  }
}
