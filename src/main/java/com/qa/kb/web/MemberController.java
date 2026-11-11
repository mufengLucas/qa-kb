package com.qa.kb.web;

import com.qa.kb.user.UserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录即可访问的成员目录。
 *
 * <p>{@code /api/users/**} 被 {@code SecurityConfig} 限定为 ADMIN-only，而任务分配的负责人下拉
 * 需要 MEMBER 也能拉到名单，所以单独开一个只读端点；只返回启用状态成员，且绝不包含密码散列。
 */
@RestController
@RequestMapping("/api/members")
public class MemberController {

  private final UserService userService;

  public MemberController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  public List<UserService.UserView> members() {
    return userService.list().stream().filter(UserService.UserView::active).toList();
  }
}
