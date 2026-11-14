package com.qa.kb.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.PageRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.SpaceMemberMapper;
import com.qa.kb.security.SessionUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpaceAccessService：空间级权限判定")
class SpaceAccessServiceTest {

  @Mock private SpaceMapper spaceMapper;
  @Mock private SpaceMemberMapper memberMapper;
  @Mock private PageMapper pageMapper;

  private SpaceAccessService access;

  private static final SessionUser ADMIN = new SessionUser(1L, "admin", "管理员", Role.ADMIN);
  private static final SessionUser MEMBER = new SessionUser(2L, "alice", "爱丽丝", Role.MEMBER);
  private static final SessionUser VIEWER = new SessionUser(3L, "guest", "访客", Role.VIEWER);

  private static final SpaceRow OPEN = new SpaceRow(1L, "开放", "open", "", "", 0, false);
  private static final SpaceRow SECRET = new SpaceRow(2L, "机密", "secret", "", "", 0, true);

  @BeforeEach
  void setUp() {
    access = new SpaceAccessService(spaceMapper, memberMapper, pageMapper);
  }

  /* ------------------------------------------------------------------ 读 */

  @Test
  void openSpaceIsReadableByAnyoneWithoutMembershipLookup() {
    access.checkRead(VIEWER, OPEN);
    access.checkRead(MEMBER, OPEN);
    access.checkRead(ADMIN, OPEN);

    verifyNoInteractions(memberMapper);
  }

  @Test
  void restrictedSpaceRejectsNonMember() {
    when(memberMapper.findRole(2L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> access.checkRead(MEMBER, SECRET))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void restrictedSpaceAcceptsMemberWithAnyRole() {
    when(memberMapper.findRole(2L, 3L)).thenReturn(Optional.of("VIEWER"));

    access.checkRead(VIEWER, SECRET);
  }

  @Test
  void restrictedSpaceAcceptsGlobalAdminWithoutMembership() {
    access.checkRead(ADMIN, SECRET);

    verifyNoInteractions(memberMapper);
  }

  @Test
  void requireReadMapsMissingSpaceToNotFound() {
    when(spaceMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> access.requireRead(MEMBER, 9L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  /* ------------------------------------------------------------------ 写 */

  @Test
  void openSpaceWriteFollowsGlobalRoleWhenNoMembership() {
    when(memberMapper.findRole(1L, 2L)).thenReturn(Optional.empty());
    access.checkWrite(MEMBER, OPEN);

    when(memberMapper.findRole(1L, 3L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.checkWrite(VIEWER, OPEN))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void memberRecordOverridesGlobalRoleInBothDirections() {
    // 收紧：全局 MEMBER 在该空间只是只读 → 不可写
    when(memberMapper.findRole(1L, 2L)).thenReturn(Optional.of("VIEWER"));
    assertThatThrownBy(() -> access.checkWrite(MEMBER, OPEN))
        .isInstanceOf(ApiException.class);

    // 放宽受控：空间成员 ADMIN（全局 ADMIN 的成员记录也一致）
    when(memberMapper.findRole(2L, 2L)).thenReturn(Optional.of("ADMIN"));
    access.checkWrite(MEMBER, SECRET);
  }

  @Test
  void restrictedNonMemberCannotWriteEvenWithGlobalMemberRole() {
    when(memberMapper.findRole(2L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> access.checkWrite(MEMBER, SECRET))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  /* -------------------------------------------------------- 管理（设置/删除/成员） */

  @Test
  void adminRequiresSpaceAdminRole() {
    // 全局 MEMBER 无成员记录：有效角色 MEMBER → 无权管理
    when(memberMapper.findRole(1L, 2L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.checkAdmin(MEMBER, OPEN))
        .isInstanceOf(ApiException.class);

    // 空间成员 ADMIN → 可管理
    when(memberMapper.findRole(1L, 2L)).thenReturn(Optional.of("ADMIN"));
    access.checkAdmin(MEMBER, OPEN);
  }

  @Test
  void globalAdminAlwaysHasAdminRole() {
    assertThat(access.effectiveRole(ADMIN, SECRET)).isEqualTo(Role.ADMIN);
    access.checkAdmin(ADMIN, SECRET);
    access.checkWrite(ADMIN, SECRET);
  }

  @Test
  void effectiveRoleFallsBackToGlobalRoleWithoutMembership() {
    when(memberMapper.findRole(1L, 2L)).thenReturn(Optional.empty());

    assertThat(access.effectiveRole(MEMBER, OPEN)).isEqualTo(Role.MEMBER);
  }

  /* ------------------------------------------------------ 列表 / 页面穿透 */

  @Test
  void visibleSpaceIdsPassesAdminFlagThrough() {
    when(memberMapper.visibleSpaceIds(2L, 0)).thenReturn(List.of(1L, 3L));
    when(memberMapper.visibleSpaceIds(1L, 1)).thenReturn(List.of(1L, 2L, 3L));

    assertThat(access.visibleSpaceIds(MEMBER)).containsExactlyInAnyOrder(1L, 3L);
    assertThat(access.visibleSpaceIds(ADMIN)).containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  @Test
  void filterReadableHidesRestrictedSpacesFromNonMembers() {
    when(memberMapper.visibleSpaceIds(2L, 0)).thenReturn(List.of(1L));

    List<SpaceRow> visible = access.filterReadable(MEMBER, List.of(OPEN, SECRET));

    assertThat(visible).extracting(SpaceRow::slug).containsExactly("open");
  }

  @Test
  void filterReadablePagesDropsPagesOfHiddenSpaces() {
    when(memberMapper.visibleSpaceIds(2L, 0)).thenReturn(List.of(1L));

    PageRow inOpen = pageInSpace(10L, 1L);
    PageRow inSecret = pageInSpace(11L, 2L);

    assertThat(access.filterReadablePages(MEMBER, List.of(inOpen, inSecret)))
        .extracting(PageRow::id)
        .containsExactly(10L);
  }

  @Test
  void checkPageWriteResolvesSpaceThroughPageRow() {
    when(pageMapper.findAnyById(10L)).thenReturn(Optional.of(pageInSpace(10L, 2L)));
    when(spaceMapper.findById(2L)).thenReturn(Optional.of(SECRET));
    when(memberMapper.findRole(2L, 2L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> access.checkPageWrite(MEMBER, 10L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void checkPageReadMapsMissingPageToNotFound() {
    when(pageMapper.findAnyById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> access.checkPageRead(MEMBER, 99L))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  /* ------------------------------------------------------------------ 辅助 */

  private static PageRow pageInSpace(long pageId, long spaceId) {
    return new PageRow(
        pageId, spaceId, null, "页面", "page", "内容", "PUBLISHED", 2L, 0,
        LocalDateTime.now(), LocalDateTime.now(), null);
  }

  @Test
  void visibleSpaceIdsOfEmptyFilterReturnsEmptySet() {
    // 防御：过滤器不应在集合为空时误放行
    when(memberMapper.visibleSpaceIds(3L, 0)).thenReturn(List.of());

    Set<Long> ids = access.visibleSpaceIds(VIEWER);

    assertThat(ids).isEmpty();
    verify(memberMapper).visibleSpaceIds(3L, 0);
  }
}
