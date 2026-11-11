package com.qa.kb.web;

import com.qa.kb.auth.Role;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only member management. Enforced by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/users")
public class UserController {

  public record CreateUserRequest(
      @NotBlank String login,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String password,
      String role) {}

  public record UpdateUserRequest(
      @NotBlank @Size(max = 100) String name, String role, Boolean active) {}

  public record ResetPasswordRequest(@NotBlank String newPassword) {}

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  public List<UserService.UserView> list() {
    return userService.list();
  }

  @PostMapping
  public ResponseEntity<UserService.UserView> create(@Valid @RequestBody CreateUserRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userService.create(body.login(), body.name(), body.password(), body.role()));
  }

  @PutMapping("/{id}")
  public UserService.UserView update(
      @PathVariable long id, @Valid @RequestBody UpdateUserRequest body) {
    Role role = body.role() == null || body.role().isBlank() ? null : Role.parse(body.role());
    boolean active = body.active() == null || body.active();
    return userService.update(id, body.name(), role, active);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
    SessionUser operator = CurrentUser.require(authentication);
    userService.delete(id, operator.id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/password")
  public ResponseEntity<Void> resetPassword(
      @PathVariable long id, @Valid @RequestBody ResetPasswordRequest body) {
    userService.resetPassword(id, body.newPassword());
    return ResponseEntity.noContent().build();
  }
}
