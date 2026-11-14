package com.qa.kb.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import com.qa.kb.model.RevisionRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.model.TagCount;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.RevisionMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.TagMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("PageService")
class PageServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Mock private PageMapper pageMapper;
  @Mock private RevisionMapper revisionMapper;
  @Mock private SpaceMapper spaceMapper;
  @Mock private TagMapper tagMapper;

  private PageService service;

  @BeforeEach
  void setUp() {
    service = new PageService(pageMapper, revisionMapper, spaceMapper, tagMapper, CLOCK);
  }

  private static PageRow page(long id, long spaceId, Long parentId, String title) {
    return new PageRow(
        id,
        spaceId,
        parentId,
        title,
        "page",
        "content",
        "PUBLISHED",
        1L,
        0,
        LocalDateTime.now(),
        LocalDateTime.now(),
        null);
  }

  @Test
  void createRejectsBlankTitle() {
    assertThatThrownBy(() -> service.create(1, null, "   ", "x", "PUBLISHED", 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(9, null, "Title", "x", "PUBLISHED", 1L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createRejectsParentFromAnotherSpace() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(new SpaceRow(1L, "S", "s", "", "", 0, false)));
    when(pageMapper.findById(5L)).thenReturn(Optional.of(page(5, 2, null, "Other")));

    assertThatThrownBy(() -> service.create(1, 5L, "Title", "x", "PUBLISHED", 1L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createInsertsPageAndInitialRevision() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(new SpaceRow(1L, "S", "s", "", "", 0, false)));
    when(pageMapper.insert(1L, null, "登录接口", "page", "内容", "PUBLISHED", 7L, 0))
        .thenReturn(1);
    when(pageMapper.lastInsertId()).thenReturn(42L);
    when(pageMapper.findById(42L)).thenReturn(Optional.of(page(42, 1, null, "登录接口")));

    PageRow created = service.create(1, null, "登录接口", "内容", "PUBLISHED", 7L);

    assertThat(created.id()).isEqualTo(42L);
    verify(revisionMapper).insert(42L, "登录接口", "内容", 7L);
  }

  @Test
  void createDefaultsStatusToPublishedAndContentToEmpty() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(new SpaceRow(1L, "S", "s", "", "", 0, false)));
    when(pageMapper.insert(eq(1L), eq(null), eq("T"), eq("t"), eq(""), eq("PUBLISHED"), eq(1L), eq(0)))
        .thenReturn(1);
    when(pageMapper.lastInsertId()).thenReturn(2L);
    when(pageMapper.findById(2L)).thenReturn(Optional.of(page(2, 1, null, "T")));

    service.create(1, null, "T", null, null, 1L);

    verify(pageMapper).insert(1L, null, "T", "t", "", "PUBLISHED", 1L, 0);
  }

  @Test
  void createRejectsUnknownParent() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(new SpaceRow(1L, "S", "s", "", "", 0, false)));
    when(pageMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(1, 9L, "T", "x", "PUBLISHED", 1L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void updateWritesANewRevision() {
    PageRow existing = page(3, 1, null, "旧标题");
    when(pageMapper.findById(3L)).thenReturn(Optional.of(existing));
    when(pageMapper.update(eq(3L), eq("新标题"), anyString(), eq("新内容"), eq("PUBLISHED"), any()))
        .thenReturn(1);
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "新标题")));

    service.update(3, "新标题", "新内容", "PUBLISHED", 8L);

    verify(revisionMapper).insert(3L, "新标题", "新内容", 8L);
  }

  @Test
  void updateRejectsUnknownPage() {
    when(pageMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(99, "T", "x", "PUBLISHED", 1L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void updateRejectsInvalidStatus() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));

    assertThatThrownBy(() -> service.update(3, "T", "x", "SOMETHING", 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void softDeleteMarksPageDeleted() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));
    when(pageMapper.softDelete(eq(3L), any(LocalDateTime.class))).thenReturn(1);

    service.softDelete(3L);

    verify(pageMapper).softDelete(eq(3L), any(LocalDateTime.class));
  }

  @Test
  void softDeleteUnknownPageIsNotFound() {
    when(pageMapper.findById(3L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.softDelete(3L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(pageMapper, never()).softDelete(anyLong(), any());
  }

  @Test
  void restoreUsesFindAnyByIdSoDeletedPagesCanBeFound() {
    when(pageMapper.findAnyById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));

    service.restore(3L);

    verify(pageMapper).restore(3L);
  }

  @Test
  void restoreUnknownPageIsNotFound() {
    when(pageMapper.findAnyById(3L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.restore(3L))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void treeBuildsNestedStructure() {
    when(pageMapper.listBySpace(1L))
        .thenReturn(
            List.of(
                new PageSummary(1L, null, "Root", "root", 0),
                new PageSummary(2L, 1L, "Child", "child", 0)));

    List<PageTreeBuilder.PageNode> tree = service.tree(1L);

    assertThat(tree).hasSize(1);
    assertThat(tree.get(0).children()).hasSize(1);
  }

  @Test
  void rollbackRejectsRevisionFromAnotherPage() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));
    when(revisionMapper.findById(9L))
        .thenReturn(Optional.of(new RevisionRow(9L, 99L, "x", "y", 1L, LocalDateTime.now())));

    assertThatThrownBy(() -> service.rollback(3, 9, 1))
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void rollbackChecksOutRevisionAndRecordsNewRevision() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));
    when(revisionMapper.findById(9L))
        .thenReturn(Optional.of(new RevisionRow(9L, 3L, "旧版", "旧内容", 1L, LocalDateTime.now())));
    when(pageMapper.update(eq(3L), eq("旧版"), anyString(), eq("旧内容"), eq("PUBLISHED"), any()))
        .thenReturn(1);
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "旧版")));

    service.rollback(3, 9, 5L);

    verify(revisionMapper).insert(3L, "旧版", "旧内容", 5L);
  }

  @Test
  void setTagsTrimsDedupesAndReplacesLinks() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));
    when(tagMapper.upsert("api")).thenReturn(1);
    when(tagMapper.upsert("测试")).thenReturn(1);
    when(tagMapper.findIdByName("api")).thenReturn(11L);
    when(tagMapper.findIdByName("测试")).thenReturn(12L);

    service.setTags(3L, List.of(" api ", "测试", "api", "  "));

    verify(tagMapper).detachAll(3L);
    verify(tagMapper).attach(3L, 11L);
    verify(tagMapper).attach(3L, 12L);
  }

  @Test
  void setTagsRejectsTooManyTags() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));
    List<String> many =
        java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i -> "t" + i).toList();

    assertThatThrownBy(() -> service.setTags(3L, many))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setTagsRejectsOverlongTagName() {
    when(pageMapper.findById(3L)).thenReturn(Optional.of(page(3, 1, null, "T")));

    assertThatThrownBy(() -> service.setTags(3L, List.of("x".repeat(51))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tagsAreReadThroughTheMapper() {
    when(tagMapper.listByPage(3L)).thenReturn(List.of(new TagCount("api", 1L)));

    assertThat(service.tagsOf(3L)).extracting(TagCount::name).containsExactly("api");
  }

  @Test
  void allTagsAreScopedToVisibleSpaces() {
    when(tagMapper.listAllInSpaces(java.util.Set.of(1L)))
        .thenReturn(List.of(new TagCount("api", 3L)));

    assertThat(service.allTags(java.util.Set.of(1L)))
        .extracting(TagCount::name)
        .containsExactly("api");
  }

  @Test
  void allTagsShortCircuitsOnEmptySpaceSet() {
    assertThat(service.allTags(java.util.Set.of())).isEmpty();
    verify(tagMapper, never()).listAllInSpaces(any());
  }
}
