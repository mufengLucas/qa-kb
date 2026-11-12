package com.qa.kb.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.AttachmentRow;
import com.qa.kb.model.PageRow;
import com.qa.kb.persistence.AttachmentMapper;
import com.qa.kb.persistence.PageMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("AttachmentService")
class AttachmentServiceTest {

  @Mock private AttachmentMapper attachmentMapper;
  @Mock private PageMapper pageMapper;

  @TempDir Path tempDir;

  private AttachmentService service;

  @BeforeEach
  void setUp() {
    service = new AttachmentService(attachmentMapper, pageMapper, tempDir);
  }

  private static PageRow page(long id) {
    return new PageRow(
        id, 1L, null, "页面", "page-" + id, "# body", "PUBLISHED", 1L, 0,
        LocalDateTime.of(2026, 11, 12, 9, 0),
        LocalDateTime.of(2026, 11, 12, 9, 0),
        null);
  }

  private static AttachmentRow row(long id, long pageId, String storedName) {
    return new AttachmentRow(
        id, pageId, "报告.txt", storedName, 5L, "text/plain", 1L,
        LocalDateTime.of(2026, 11, 12, 9, 0));
  }

  // ---------- 文件名清洗（static，直接钉住规则） ----------

  @Test
  void cleanFilenameStripsAnyPathPrefix() {
    assertThat(AttachmentService.cleanFilename("C:\\fakepath\\报告.txt")).isEqualTo("报告.txt");
    assertThat(AttachmentService.cleanFilename("../../etc/passwd")).isEqualTo("passwd");
    assertThat(AttachmentService.cleanFilename("/var/tmp/a.md")).isEqualTo("a.md");
  }

  @Test
  void cleanFilenameFallsBackWhenBlankOrDot() {
    assertThat(AttachmentService.cleanFilename(null)).isEqualTo("未命名");
    assertThat(AttachmentService.cleanFilename("   ")).isEqualTo("未命名");
    assertThat(AttachmentService.cleanFilename(".")).isEqualTo("未命名");
    assertThat(AttachmentService.cleanFilename("..")).isEqualTo("未命名");
    assertThat(AttachmentService.cleanFilename("dir/")).isEqualTo("未命名");
  }

  @Test
  void cleanFilenameTruncatesOverlongKeepingExtension() {
    String longName = "长".repeat(300) + ".pdf";

    String cleaned = AttachmentService.cleanFilename(longName);

    assertThat(cleaned).hasSize(AttachmentService.MAX_FILENAME_LENGTH).endsWith(".pdf");
  }

  // ---------- list ----------

  @Test
  void listRejectsMissingOrDeletedPage() {
    when(pageMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.list(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(attachmentMapper, never()).listByPage(anyLong());
  }

  @Test
  void listDelegatesForLivePage() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3L)));
    when(attachmentMapper.listByPage(3L)).thenReturn(List.of(row(1L, 3L, "u1")));

    assertThat(service.list(3L)).extracting(AttachmentRow::id).containsExactly(1L);
  }

  // ---------- save ----------

  @Test
  void saveRejectsMissingPageBeforeTouchingDisk() {
    when(pageMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.save(9L, "a.txt", "text/plain", new byte[] {1}, 1L))
        .isInstanceOf(ApiException.class);
    verify(attachmentMapper, never()).insert(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyLong());
  }

  @Test
  void saveRejectsEmptyAndOversizedContent() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3L)));

    assertThatThrownBy(() -> service.save(3L, "a.txt", null, new byte[0], 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.save(
                    3L, "a.txt", null, new byte[(int) AttachmentService.MAX_FILE_SIZE + 1], 1L))
        .isInstanceOf(IllegalArgumentException.class);
    verify(attachmentMapper, never()).insert(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyLong());
  }

  @Test
  void saveWritesFileThenMetadataAndReturnsRow() throws IOException {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3L)));
    when(attachmentMapper.insert(eq(3L), eq("报告.txt"), anyString(), eq(5L), eq("text/plain"), eq(1L)))
        .thenReturn(1);
    when(attachmentMapper.lastInsertId()).thenReturn(7L);
    when(attachmentMapper.findById(7L)).thenReturn(Optional.of(row(7L, 3L, "stored-7")));

    AttachmentRow saved = service.save(3L, "C:\\fakepath\\报告.txt", "text/plain", "hello".getBytes(), 1L);

    assertThat(saved.id()).isEqualTo(7L);
    verify(attachmentMapper).insert(eq(3L), eq("报告.txt"), anyString(), eq(5L), eq("text/plain"), eq(1L));
    try (var files = Files.list(tempDir)) {
      assertThat(files).hasSize(1);
    }
  }

  @Test
  void saveCleansUpFileWhenMetadataInsertFails() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3L)));
    when(attachmentMapper.insert(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyLong()))
        .thenThrow(new DuplicateKeyException("uk"));

    assertThatThrownBy(() -> service.save(3L, "a.txt", "text/plain", "x".getBytes(), 1L))
        .isInstanceOf(DuplicateKeyException.class);

    // 不留孤儿文件
    try (var files = Files.list(tempDir)) {
      assertThat(files).isEmpty();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // ---------- open ----------

  @Test
  void openRejectsUnknownAttachment() {
    when(attachmentMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.open(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void openRejectsWhenFileMissingOnDisk() throws IOException {
    when(attachmentMapper.findById(1L)).thenReturn(Optional.of(row(1L, 3L, "gone-uuid")));

    assertThatThrownBy(() -> service.open(1L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).contains("丢失"));
  }

  @Test
  void openRejectsPathTraversalStoredName() {
    when(attachmentMapper.findById(1L))
        .thenReturn(Optional.of(row(1L, 3L, "..\\..\\evil")));

    assertThatThrownBy(() -> service.open(1L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void openReturnsMetadataAndPath() throws IOException {
    Files.write(tempDir.resolve("stored-1"), "hello".getBytes());
    when(attachmentMapper.findById(1L)).thenReturn(Optional.of(row(1L, 3L, "stored-1")));

    AttachmentService.Download download = service.open(1L);

    assertThat(download.row().id()).isEqualTo(1L);
    assertThat(Files.readString(download.path())).isEqualTo("hello");
  }

  // ---------- delete ----------

  @Test
  void deleteRejectsUnknownAttachment() {
    when(attachmentMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ApiException.class);
    verify(attachmentMapper, never()).delete(anyLong());
  }

  @Test
  void deleteRemovesRowAndFile() throws IOException {
    Files.write(tempDir.resolve("stored-1"), "hello".getBytes());
    when(attachmentMapper.findById(1L)).thenReturn(Optional.of(row(1L, 3L, "stored-1")));

    service.delete(1L);

    verify(attachmentMapper).delete(1L);
    assertThat(Files.exists(tempDir.resolve("stored-1"))).isFalse();
  }
}
