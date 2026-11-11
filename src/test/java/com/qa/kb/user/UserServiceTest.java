package com.qa.kb.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.UserRow;
import com.qa.kb.persistence.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

  @Mock private UserMapper userMapper;
  @Mock private PasswordEncoder passwordEncoder;

  private UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(userMapper, passwordEncoder);
  }

  private static UserRow user(long id, String login, Role role, boolean active) {
    return new UserRow(
        id, login, "成员" + id, "hash", role, active, LocalDateTime.of(2026, 1, 1, 9, 0));
  }

  @Test
  void listNeverExposesPasswordHashes() {
    when(userMapper.findAll()).thenReturn(List.of(user(1, "alice", Role.ADMIN, true)));

    List<UserService.UserView> users = service.list();

    assertThat(users).extracting(UserService.UserView::login).containsExactly("alice");
    assertThat(users.get(0).toString()).doesNotContain("hash");
  }

  @Test
  void createRejectsBlankLogin() {
    assertThatThrownBy(() -> service.create("  ", "Alice", "Str0ngPass!x", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userMapper, never()).insert(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void createRejectsInvalidLoginCharacters() {
    assertThatThrownBy(() -> service.create("Alice Smith", "Alice", "Str0ngPass!x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsTooShortLogin() {
    assertThatThrownBy(() -> service.create("ab", "Alice", "Str0ngPass!x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createLowercasesAndTrimsLogin() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("Str0ngPass!x")).thenReturn("ENC");
    when(userMapper.insert("alice", "Alicia", "ENC", Role.MEMBER, true)).thenReturn(1);
    when(userMapper.lastInsertId()).thenReturn(5L);
    when(userMapper.findById(5L)).thenReturn(Optional.of(user(5, "alice", Role.MEMBER, true)));

    assertThat(service.create("  Alice  ", " Alicia ", "Str0ngPass!x", null).id()).isEqualTo(5L);
  }

  @Test
  void createRejectsDuplicateLogin() {
    when(userMapper.findByLogin("alice")).thenReturn(Optional.of(user(1, "alice", Role.MEMBER, true)));

    assertThatThrownBy(() -> service.create("alice", "Alice", "Str0ngPass!x", null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void createEnforcesPasswordPolicy() {
    assertThatThrownBy(() -> service.create("bob", "Bob", "short", null))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(userMapper, never()).insert(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void createRejectsPasswordEqualToLogin() {
    assertThatThrownBy(() -> service.create("alice1234", "Alice", "Alice1234", null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void createDefaultsRoleToMember() {
    when(userMapper.findByLogin("bob")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("Str0ngPass!x")).thenReturn("ENC");
    when(userMapper.insert("bob", "Bob", "ENC", Role.MEMBER, true)).thenReturn(1);
    when(userMapper.lastInsertId()).thenReturn(6L);
    when(userMapper.findById(6L)).thenReturn(Optional.of(user(6, "bob", Role.MEMBER, true)));

    service.create("bob", "Bob", "Str0ngPass!x", null);

    verify(userMapper).insert("bob", "Bob", "ENC", Role.MEMBER, true);
  }

  @Test
  void createRejectsUnknownRole() {
    assertThatThrownBy(() -> service.create("bob", "Bob", "Str0ngPass!x", "SUPERUSER"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateRejectsUnknownUser() {
    when(userMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(9L, "Name", Role.MEMBER, true))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void updateCannotDemoteTheLastActiveAdmin() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)));
    when(userMapper.countActiveAdmins()).thenReturn(1L);

    assertThatThrownBy(() -> service.update(1L, "管理员", Role.MEMBER, true))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT));
    verify(userMapper, never()).update(anyLong(), anyString(), any(), anyBoolean());
  }

  @Test
  void updateCannotDeactivateTheLastActiveAdmin() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)));
    when(userMapper.countActiveAdmins()).thenReturn(1L);

    assertThatThrownBy(() -> service.update(1L, "管理员", Role.ADMIN, false))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void updateAllowsDemotionWhenAnotherAdminExists() {
    when(userMapper.findById(1L))
        .thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)))
        .thenReturn(Optional.of(user(1, "admin", Role.MEMBER, true)));
    when(userMapper.countActiveAdmins()).thenReturn(2L);
    when(userMapper.update(1L, "管理员", Role.MEMBER, true)).thenReturn(1);

    assertThat(service.update(1L, " 管理员 ", Role.MEMBER, true).role()).isEqualTo("MEMBER");
  }

  @Test
  void updateRejectsBlankName() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "alice", Role.MEMBER, true)));

    assertThatThrownBy(() -> service.update(1L, "  ", Role.MEMBER, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deleteCannotRemoveTheLastActiveAdmin() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)));
    when(userMapper.countActiveAdmins()).thenReturn(1L);

    assertThatThrownBy(() -> service.delete(1L, 1L)).isInstanceOf(ApiException.class);
    verify(userMapper, never()).delete(anyLong());
  }

  @Test
  void deleteRemovesANonAdmin() {
    when(userMapper.findById(2L)).thenReturn(Optional.of(user(2, "bob", Role.MEMBER, true)));

    service.delete(2L, 1L);

    verify(userMapper).delete(2L);
  }

  @Test
  void deleteTransfersOwnedContentToTheOperator() {
    when(userMapper.findById(2L)).thenReturn(Optional.of(user(2, "alice", Role.MEMBER, true)));

    service.delete(2L, 1L);

    verify(userMapper).reassignAuthor(2L, 1L);
    verify(userMapper).reassignEditor(2L, 1L);
    verify(userMapper).reassignUploads(2L, 1L);
    verify(userMapper).reassignProjects(2L, 1L);
    verify(userMapper).delete(2L);
  }

  @Test
  void deleteBySelfFallsBackToAnotherActiveAdmin() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)));
    when(userMapper.countActiveAdmins()).thenReturn(2L);
    when(userMapper.findOtherActiveAdmin(1L)).thenReturn(Optional.of(3L));

    service.delete(1L, 1L);

    verify(userMapper).reassignAuthor(1L, 3L);
    verify(userMapper).reassignProjects(1L, 3L);
    verify(userMapper).delete(1L);
  }

  @Test
  void deleteBySelfWithoutAnotherAdminIsRejected() {
    when(userMapper.findById(1L)).thenReturn(Optional.of(user(1, "admin", Role.ADMIN, true)));
    when(userMapper.countActiveAdmins()).thenReturn(2L);
    when(userMapper.findOtherActiveAdmin(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(1L, 1L)).isInstanceOf(ApiException.class);
    verify(userMapper, never()).delete(anyLong());
  }

  @Test
  void resetPasswordEnforcesPolicyAndUpdatesHash() {
    when(userMapper.findById(2L)).thenReturn(Optional.of(user(2, "bob", Role.MEMBER, true)));
    when(passwordEncoder.encode("NewStr0ngPass")).thenReturn("ENC2");

    service.resetPassword(2L, "NewStr0ngPass");

    verify(userMapper).updatePassword(2L, "ENC2");
  }

  @Test
  void resetPasswordRejectsWeakPassword() {
    when(userMapper.findById(2L)).thenReturn(Optional.of(user(2, "bob", Role.MEMBER, true)));

    assertThatThrownBy(() -> service.resetPassword(2L, "123"))
        .isInstanceOf(ApiException.class);
    verify(userMapper, never()).updatePassword(anyLong(), anyString());
  }

  @Test
  void resetPasswordRejectsUnknownUser() {
    when(userMapper.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resetPassword(9L, "NewStr0ngPass"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void listIsStableAndActiveFlagIsExposed() {
    when(userMapper.findAll())
        .thenReturn(List.of(user(1, "a", Role.ADMIN, true), user(2, "b", Role.VIEWER, false)));

    assertThat(service.list()).extracting(UserService.UserView::active)
        .containsExactly(true, false);
  }

  @Test
  void createRejectsOverlongName() {
    assertThatThrownBy(() -> service.create("bob", "x".repeat(101), "Str0ngPass!x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsNullPassword() {
    assertThatThrownBy(() -> service.create("bob", "Bob", null, null))
        .isInstanceOf(ApiException.class);
  }
}
