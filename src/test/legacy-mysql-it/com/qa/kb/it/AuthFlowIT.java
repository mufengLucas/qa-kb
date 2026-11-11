package com.qa.kb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.kb.auth.AuthService;
import com.qa.kb.common.ApiException;
import com.qa.kb.security.SessionUser;
import com.qa.kb.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@EnabledIf("dockerAvailable")
@DisplayName("session authentication against MySQL")
class AuthFlowIT extends AbstractMySqlIT {

  static boolean dockerAvailable() {
    return DockerAvailability.available();
  }

  @Autowired private UserService userService;
  @Autowired private AuthService authService;

  @Test
  void loginStoresASessionThatLogoutRevokes() {
    String suffix = Long.toString(System.nanoTime());
    UserService.UserView user =
        userService.create("it_auth_" + suffix, "认证测试", "Str0ngPass!x", "MEMBER");

    AuthService.LoginResult result = authService.login(user.login(), "Str0ngPass!x", "10.1.0.1");

    assertThat(result.token()).hasSize(43);
    assertThat(authService.authenticate(result.token()))
        .map(SessionUser::id)
        .contains(user.id());

    authService.logout(result.token());

    assertThat(authService.authenticate(result.token())).isEmpty();
  }

  @Test
  void wrongPasswordIs401AndCountsTowardRateLimit() {
    String suffix = Long.toString(System.nanoTime());
    String login = "it_auth_fail_" + suffix;
    userService.create(login, "认证失败", "Str0ngPass!x", "MEMBER");
    String ip = "10.2.0." + (Math.abs(suffix.hashCode()) % 200 + 1);

    for (int attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> authService.login(login, "WrongPass!x1", ip))
          .isInstanceOf(ApiException.class)
          .satisfies(
              ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    assertThatThrownBy(() -> authService.login(login, "Str0ngPass!x", ip))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void passwordChangeInvalidatesTheOldPassword() {
    String suffix = Long.toString(System.nanoTime());
    UserService.UserView user =
        userService.create("it_auth_pw_" + suffix, "改密测试", "Str0ngPass!x", "MEMBER");

    authService.changePassword(user.id(), "Str0ngPass!x", "N3wStrongPass!");

    assertThatThrownBy(() -> authService.login(user.login(), "Str0ngPass!x", "10.3.0.1"))
        .isInstanceOf(ApiException.class);
    assertThat(authService.login(user.login(), "N3wStrongPass!", "10.3.0.2").token()).hasSize(43);
  }

  @Test
  void inactiveUsersCannotAuthenticateAndExpiredSessionsAreRejected() {
    String suffix = Long.toString(System.nanoTime());
    UserService.UserView user =
        userService.create("it_auth_off_" + suffix, "停用测试", "Str0ngPass!x", "MEMBER");
    AuthService.LoginResult result = authService.login(user.login(), "Str0ngPass!x", "10.4.0.1");

    userService.update(user.id(), "停用测试", com.qa.kb.auth.Role.MEMBER, false);

    assertThat(authService.authenticate(result.token())).isEmpty();
    assertThatThrownBy(() -> authService.login(user.login(), "Str0ngPass!x", "10.4.0.2"))
        .isInstanceOf(ApiException.class);
  }
}
