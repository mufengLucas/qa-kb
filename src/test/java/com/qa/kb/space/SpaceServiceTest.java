package com.qa.kb.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.persistence.SpaceMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpaceService")
class SpaceServiceTest {

  @Mock private SpaceMapper spaceMapper;

  private SpaceService service;

  @BeforeEach
  void setUp() {
    service = new SpaceService(spaceMapper);
  }

  private static SpaceRow space(long id, String slug) {
    return new SpaceRow(id, "空间" + id, slug, "", "", 0);
  }

  @Test
  void listDelegates() {
    when(spaceMapper.findAll()).thenReturn(List.of(space(1, "qa")));

    assertThat(service.list()).extracting(SpaceRow::slug).containsExactly("qa");
  }

  @Test
  void getRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createRejectsBlankName() {
    assertThatThrownBy(() -> service.create("  ", "qa", "", "", 0))
        .isInstanceOf(IllegalArgumentException.class);
    verify(spaceMapper, never()).insert(any(), any(), any(), any(), anyInt());
  }

  @Test
  void createDerivesSlugFromNameWhenMissing() {
    when(spaceMapper.findBySlug("test-guide")).thenReturn(Optional.empty());
    when(spaceMapper.insert("Test Guide", "test-guide", "", "", 0)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "test-guide")));

    assertThat(service.create("Test Guide", null, "", "", 0).id()).isEqualTo(5L);
  }

  @Test
  void createRejectsDuplicateSlug() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.of(space(1, "qa")));

    assertThatThrownBy(() -> service.create("QA", "qa", "", "", 0))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void createRejectsInvalidSlug() {
    assertThatThrownBy(() -> service.create("QA", "QA Space!", "", "", 0))
        .isInstanceOf(IllegalArgumentException.class);
    verify(spaceMapper, never()).insert(any(), any(), any(), any(), anyInt());
  }

  @Test
  void createTrimsFieldsAndClampsSort() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert("QA", "qa", "🧪", "说明", 0)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "qa")));

    service.create("  QA  ", " qa ", " 🧪 ", " 说明 ", -3);

    verify(spaceMapper).insert("QA", "qa", "🧪", "说明", 0);
  }

  @Test
  void createRejectsOverlongDescription() {
    assertThatThrownBy(() -> service.create("QA", "qa", "", "x".repeat(501), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateChecksExistenceThenUpdates() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(spaceMapper.update(1L, "QA2", "i", "d", 2)).thenReturn(1);

    service.update(1L, " QA2 ", "i", "d", 2);

    verify(spaceMapper).update(1L, "QA2", "i", "d", 2);
  }

  @Test
  void updateRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(9L, "QA", "", "", 0))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void deleteChecksExistence() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));

    service.delete(1L);

    verify(spaceMapper).delete(1L);
  }

  @Test
  void deleteRemovesPagesBeforeSpaceRow() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));

    service.delete(1L);

    // 顺序不可调换：先清页面（含嵌套子页）再删空间行，规避 MySQL ERROR 1452
    InOrder order = inOrder(spaceMapper);
    order.verify(spaceMapper).deletePagesBySpace(1L);
    order.verify(spaceMapper).delete(1L);
  }

  @Test
  void deleteRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(spaceMapper, never()).deletePagesBySpace(anyLong());
    verify(spaceMapper, never()).delete(anyLong());
  }

  @Test
  void mapsDuplicateKeyOnInsertToConflict() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenThrow(new DuplicateKeyException("uk_spaces_slug"));

    assertThatThrownBy(() -> service.create("QA", "qa", "", "", 0))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }
}
