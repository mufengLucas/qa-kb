package com.qa.kb.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.auth.Role;
import com.qa.kb.common.GlobalExceptionHandler;
import com.qa.kb.user.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController")
class UserControllerTest {

  @Mock private UserService userService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new UserController(userService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static UserService.UserView view(long id, String login) {
    return new UserService.UserView(id, login, "成员" + id, "MEMBER", true);
  }

  @Test
  void listNeverSerializesPasswordFields() throws Exception {
    when(userService.list()).thenReturn(List.of(view(2, "alice")));

    mvc.perform(get("/api/users").principal(TestAuth.ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].login").value("alice"))
        .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$[0].password").doesNotExist());
  }

  @Test
  void createReturnsCreated() throws Exception {
    when(userService.create("bob", "Bob", "Str0ngPass!x", "MEMBER"))
        .thenReturn(view(3, "bob"));

    mvc.perform(
            post("/api/users")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"login\":\"bob\",\"name\":\"Bob\",\"password\":\"Str0ngPass!x\","
                        + "\"role\":\"MEMBER\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.login").value("bob"));
  }

  @Test
  void createRejectsMissingFields() throws Exception {
    mvc.perform(
            post("/api/users")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"bob\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation_failed"));
  }

  @Test
  void updateParsesRoleAndDefaultsActiveToTrue() throws Exception {
    when(userService.update(2L, "Alice", Role.ADMIN, true)).thenReturn(view(2, "alice"));

    mvc.perform(
            put("/api/users/2")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void updateRejectsUnknownRole() throws Exception {
    mvc.perform(
            put("/api/users/2")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\",\"role\":\"OWNER\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/users/2").principal(TestAuth.ADMIN))
        .andExpect(status().isNoContent());

    verify(userService).delete(2L, 1L);
  }

  @Test
  void resetPasswordReturnsNoContent() throws Exception {
    mvc.perform(
            post("/api/users/2/password")
                .principal(TestAuth.ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"New0Pass!x\"}"))
        .andExpect(status().isNoContent());

    verify(userService).resetPassword(eq(2L), any());
  }
}
