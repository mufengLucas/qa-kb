package com.qa.kb.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.SpaceMemberRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.model.UserRow;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.SpaceMemberMapper;
import com.qa.kb.persistence.UserMapper;
import java.time.LocalDateTime;
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
  @Mock private SpaceMemberMapper memberMapper;
  @Mock private UserMapper userMapper;

  private SpaceService service;

  @BeforeEach
  void setUp() {
    service = new SpaceService(spaceMapper, memberMapper, userMapper);
  }

  private static SpaceRow space(long id, String slug) {
    return new SpaceRow(id, "空间" + id, slug, "", "", 0, false);
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
    assertThatThrownBy(() -> service.create("  ", "qa", "", "", 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(spaceMapper, never()).insert(any(), any(), any(), any(), anyInt(), anyBoolean());
  }

  @Test
  void createDerivesSlugFromNameWhenMissing() {
    when(spaceMapper.findBySlug("test-guide")).thenReturn(Optional.empty());
    when(spaceMapper.insert("Test Guide", "test-guide", "", "", 0, false)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "test-guide")));

    assertThat(service.create("Test Guide", null, "", "", 0, false, null).id()).isEqualTo(5L);
  }

  @Test
  void createRejectsDuplicateSlug() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.of(space(1, "qa")));

    assertThatThrownBy(() -> service.create("QA", "qa", "", "", 0, false, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void createRejectsInvalidSlug() {
    assertThatThrownBy(() -> service.create("QA", "QA Space!", "", "", 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(spaceMapper, never()).insert(any(), any(), any(), any(), anyInt(), anyBoolean());
  }

  @Test
  void createTrimsFieldsAndClampsSort() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert("QA", "qa", "🧪", "说明", 0, false)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "qa")));

    service.create("  QA  ", " qa ", " 🧪 ", " 说明 ", -3, false, null);

    verify(spaceMapper).insert("QA", "qa", "🧪", "说明", 0, false);
  }

  @Test
  void createRejectsOverlongDescription() {
    assertThatThrownBy(() -> service.create("QA", "qa", "", "x".repeat(501), 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createPersistsRestrictedFlag() {
    when(spaceMapper.findBySlug("secret")).thenReturn(Optional.empty());
    when(spaceMapper.insert("机密", "secret", "", "", 0, true)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(7L);
    when(spaceMapper.findById(7L))
        .thenReturn(Optional.of(new SpaceRow(7L, "机密", "secret", "", "", 0, true)));

    SpaceRow created = service.create("机密", "secret", "", "", 0, true, null);

    verify(spaceMapper).insert("机密", "secret", "", "", 0, true);
    assertThat(created.restricted()).isTrue();
  }

  @Test
  void createAddsOwnerAsSpaceAdmin() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert("QA", "qa", "", "", 0, true)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "qa")));

    service.create("QA", null, "", "", 0, true, 2L);

    // 创建者自动入组空间 ADMIN：受限空间不会把创建者自己锁在门外
    verify(memberMapper).upsert(5L, 2L, "ADMIN");
  }

  @Test
  void createWithoutOwnerSkipsMembership() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert("QA", "qa", "", "", 0, false)).thenReturn(1);
    when(spaceMapper.lastInsertId()).thenReturn(5L);
    when(spaceMapper.findById(5L)).thenReturn(Optional.of(space(5, "qa")));

    service.create("QA", null, "", "", 0, false, null);

    verify(memberMapper, never()).upsert(anyLong(), anyLong(), anyString());
  }

  @Test
  void updateChecksExistenceThenUpdates() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(spaceMapper.update(1L, "QA2", "i", "d", 2, true)).thenReturn(1);

    service.update(1L, " QA2 ", "i", "d", 2, true);

    verify(spaceMapper).update(1L, "QA2", "i", "d", 2, true);
  }

  @Test
  void updateRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(9L, "QA", "", "", 0, false))
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
  void deleteRemovesMembersThenPagesThenSpaceRow() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));

    service.delete(1L);

    // 顺序不可调换：先清成员与页面（含嵌套子页）再删空间行，规避 MySQL ERROR 1452
    InOrder order = inOrder(memberMapper, spaceMapper);
    order.verify(memberMapper).deleteBySpace(1L);
    order.verify(spaceMapper).deletePagesBySpace(1L);
    order.verify(spaceMapper).delete(1L);
  }

  @Test
  void deleteRejectsUnknownSpace() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(memberMapper, never()).deleteBySpace(anyLong());
    verify(spaceMapper, never()).deletePagesBySpace(anyLong());
    verify(spaceMapper, never()).delete(anyLong());
  }

  @Test
  void mapsDuplicateKeyOnInsertToConflict() {
    when(spaceMapper.findBySlug("qa")).thenReturn(Optional.empty());
    when(spaceMapper.insert(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean()))
        .thenThrow(new DuplicateKeyException("uk_spaces_slug"));

    assertThatThrownBy(() -> service.create("QA", "qa", "", "", 0, false, null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  /* ------------------------------------------------- 空间成员（Phase 3） */

  @Test
  void listMembersChecksSpaceThenDelegates() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(memberMapper.listBySpace(1L))
        .thenReturn(List.of(new SpaceMemberRow(1L, 2L, "alice", "爱丽丝", "MEMBER")));

    assertThat(service.listMembers(1L)).extracting(SpaceMemberRow::login).containsExactly("alice");
  }

  @Test
  void upsertMemberInsertsNewMembership() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(userMapper.findById(2L)).thenReturn(Optional.of(userRow(2L, "alice")));
    when(memberMapper.listBySpace(1L))
        .thenReturn(List.of(new SpaceMemberRow(1L, 2L, "alice", "爱丽丝", "VIEWER")));

    SpaceMemberRow member = service.upsertMember(1L, 2L, "viewer");

    verify(memberMapper).upsert(1L, 2L, "VIEWER");
    assertThat(member.role()).isEqualTo("VIEWER");
  }

  @Test
  void upsertMemberUpdatesRoleWhenAlreadyAMember() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(userMapper.findById(2L)).thenReturn(Optional.of(userRow(2L, "alice")));
    when(memberMapper.listBySpace(1L))
        .thenReturn(List.of(new SpaceMemberRow(1L, 2L, "alice", "爱丽丝", "ADMIN")));

    SpaceMemberRow member = service.upsertMember(1L, 2L, "ADMIN");

    // 已存在即改角色：一条方言 upsert 搞定（mapper XML），不再依赖 DuplicateKeyException
    verify(memberMapper).upsert(1L, 2L, "ADMIN");
    assertThat(member.role()).isEqualTo("ADMIN");
  }

  @Test
  void upsertMemberRejectsUnknownUser() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(userMapper.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.upsertMember(1L, 99L, "MEMBER"))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(memberMapper, never()).upsert(anyLong(), anyLong(), anyString());
  }

  @Test
  void upsertMemberRejectsUnknownRole() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));

    assertThatThrownBy(() -> service.upsertMember(1L, 2L, "MANAGER"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(memberMapper, never()).upsert(anyLong(), anyLong(), anyString());
  }

  @Test
  void removeMemberDelegates() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(memberMapper.delete(1L, 2L)).thenReturn(1);

    service.removeMember(1L, 2L);

    verify(memberMapper).delete(1L, 2L);
  }

  @Test
  void removeMemberRejectsNonMember() {
    when(spaceMapper.findById(1L)).thenReturn(Optional.of(space(1, "qa")));
    when(memberMapper.delete(1L, 2L)).thenReturn(0);

    assertThatThrownBy(() -> service.removeMember(1L, 2L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static UserRow userRow(long id, String login) {
    return new UserRow(id, login, "成员" + id, "hash", Role.MEMBER, true, LocalDateTime.now());
  }
}
