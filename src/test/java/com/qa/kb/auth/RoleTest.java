package com.qa.kb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Role")
class RoleTest {

  @Test
  void parsesKnownRolesIgnoringCaseAndSpace() {
    assertThat(Role.parse("admin")).isEqualTo(Role.ADMIN);
    assertThat(Role.parse(" MEMBER ")).isEqualTo(Role.MEMBER);
    assertThat(Role.parse("Viewer")).isEqualTo(Role.VIEWER);
  }

  @Test
  void rejectsUnknownAndNullRoles() {
    assertThatThrownBy(() -> Role.parse("root")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Role.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void viewerCannotWrite() {
    assertThat(Role.VIEWER.canWrite()).isFalse();
    assertThat(Role.MEMBER.canWrite()).isTrue();
    assertThat(Role.ADMIN.canWrite()).isTrue();
  }

  @Test
  void onlyAdminCanAdminister() {
    assertThat(Role.VIEWER.canAdmin()).isFalse();
    assertThat(Role.MEMBER.canAdmin()).isFalse();
    assertThat(Role.ADMIN.canAdmin()).isTrue();
  }
}
