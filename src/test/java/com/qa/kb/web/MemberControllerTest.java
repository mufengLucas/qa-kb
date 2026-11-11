package com.qa.kb.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qa.kb.user.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberController")
class MemberControllerTest {

  @Mock private UserService userService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new MemberController(userService)).build();
  }

  @Test
  void membersReturnsOnlyActiveUsers() throws Exception {
    when(userService.list())
        .thenReturn(
            List.of(
                new UserService.UserView(1L, "admin", "管理员", "ADMIN", true),
                new UserService.UserView(2L, "bob", "鲍勃", "MEMBER", true),
                new UserService.UserView(3L, "old", "停用成员", "MEMBER", false)));

    mvc.perform(get("/api/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].login").value("admin"))
        .andExpect(jsonPath("$[1].login").value("bob"));
  }

  @Test
  void membersNeverExposePasswordMaterial() throws Exception {
    when(userService.list())
        .thenReturn(List.of(new UserService.UserView(1L, "admin", "管理员", "ADMIN", true)));

    // UserView 不含 passwordHash 字段——序列化响应里绝不能出现密码散列
    mvc.perform(get("/api/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$[0].password").doesNotExist());
  }
}
