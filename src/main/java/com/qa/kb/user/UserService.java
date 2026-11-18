package com.qa.kb.user;

import com.qa.kb.auth.PasswordPolicy;
import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.UserRow;
import com.qa.kb.persistence.UserMapper;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team member administration.
 *
 * <p>Two invariants protect the deployment: login names are unique and normalized to lowercase, and
 * the last active ADMIN can never be demoted, deactivated or deleted (otherwise nobody could manage
 * the instance).
 */
public class UserService {

  public static final int MAX_NAME_LENGTH = 100;
  public static final int MAX_EMAIL_LENGTH = 200;
  private static final Pattern LOGIN_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

  public record UserView(long id, String login, String name, String email, String role,
      boolean active) {
    public static UserView of(UserRow row) {
      return new UserView(
          row.id(), row.login(), row.name(), row.email(), row.role().name(), row.active());
    }
  }

  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
    this.userMapper = userMapper;
    this.passwordEncoder = passwordEncoder;
  }

  public List<UserView> list() {
    return userMapper.findAll().stream().map(UserView::of).toList();
  }

  @Transactional
  public UserView create(String login, String name, String rawPassword, String role) {
    return create(login, name, rawPassword, role, "");
  }

  /**
   * 创建成员（含邮箱）：邮箱可留空（= 不接收外发邮件），填了就按 {@link #MAX_EMAIL_LENGTH}
   * 与格式校验——外发投递失败往往源于脏地址，脏数据在入口就挡住。
   */
  @Transactional
  public UserView create(String login, String name, String rawPassword, String role, String email) {
    String cleanLogin = normalizeLogin(login);
    String cleanName = requireName(name);
    String cleanEmail = normalizeEmail(email);
    Role parsedRole = role == null || role.isBlank() ? Role.MEMBER : Role.parse(role);
    requirePassword(cleanLogin, rawPassword);

    if (userMapper.findByLogin(cleanLogin).isPresent()) {
      throw ApiException.conflict("登录名已存在: " + cleanLogin);
    }

    userMapper.insert(
        cleanLogin, cleanName, cleanEmail, passwordEncoder.encode(rawPassword), parsedRole, true);
    long id = userMapper.lastInsertId();
    return userMapper
        .findById(id)
        .map(UserView::of)
        .orElseThrow(() -> ApiException.notFound("用户不存在"));
  }

  /** 更新成员（保留原邮箱）：不涉及邮箱的调用方走这个重载。 */
  @Transactional
  public UserView update(long id, String name, Role role, boolean active) {
    return update(id, name, role, active, null);
  }

  /**
   * 更新成员（含邮箱）。{@code email == null} 表示不动邮箱；空串表示清空；
   * 非空则校验后覆盖——前端不回传该字段时不会把已有邮箱抹掉。
   */
  @Transactional
  public UserView update(long id, String name, Role role, boolean active, String email) {
    UserRow existing =
        userMapper.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
    String cleanName = requireName(name);
    Role nextRole = role == null ? existing.role() : role;
    String nextEmail = email == null ? existing.email() : normalizeEmail(email);

    boolean removesActiveAdmin =
        existing.role() == Role.ADMIN
            && existing.active()
            && (!nextRole.canAdmin() || !active);
    if (removesActiveAdmin && userMapper.countActiveAdmins() <= 1) {
      throw ApiException.conflict("至少要保留一名启用状态的管理员");
    }

    userMapper.update(id, cleanName, nextEmail, nextRole, active);
    return userMapper.findById(id).map(UserView::of).orElseThrow(() -> ApiException.notFound("用户不存在"));
  }

  /**
   * 删除成员，同时把其名下的页面、修订、附件、项目转移给操作者——这些外键是 RESTRICT，
   * 直接删会被数据库拒绝；知识库内容不能随成员离职丢失。
   *
   * @param id 被删除的用户
   * @param operatorId 执行删除的管理员（内容接管者）
   */
  @Transactional
  public void delete(long id, long operatorId) {
    UserRow existing =
        userMapper.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
    if (existing.role() == Role.ADMIN
        && existing.active()
        && userMapper.countActiveAdmins() <= 1) {
      throw ApiException.conflict("至少要保留一名启用状态的管理员");
    }
    // 管理员注销自己时不能把内容转给自己，改由另一名启用管理员接管
    long target =
        operatorId != id
            ? operatorId
            : userMapper
                .findOtherActiveAdmin(id)
                .orElseThrow(() -> ApiException.conflict("没有其他管理员可以接管该用户的内容"));
    userMapper.reassignAuthor(id, target);
    userMapper.reassignEditor(id, target);
    userMapper.reassignUploads(id, target);
    userMapper.reassignProjects(id, target);
    userMapper.delete(id);
  }

  @Transactional
  public void resetPassword(long id, String newPassword) {
    UserRow existing =
        userMapper.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
    requirePassword(existing.login(), newPassword);
    userMapper.updatePassword(id, passwordEncoder.encode(newPassword));
  }

  private static String normalizeLogin(String login) {
    if (login == null || login.isBlank()) {
      throw new IllegalArgumentException("登录名不能为空");
    }
    String clean = login.strip().toLowerCase(Locale.ROOT);
    if (!LOGIN_PATTERN.matcher(clean).matches()) {
      throw new IllegalArgumentException("登录名需为 3-64 位小写字母、数字或 . _ -");
    }
    return clean;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("姓名不能为空");
    }
    String clean = name.strip();
    if (clean.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("姓名不能超过 " + MAX_NAME_LENGTH + " 个字符");
    }
    return clean;
  }

  /** 邮箱清洗：null/空白统一为 ""（不收邮件）；非空则限长并校验最简格式。 */
  private static String normalizeEmail(String email) {
    if (email == null) {
      return "";
    }
    String clean = email.strip();
    if (clean.isEmpty()) {
      return "";
    }
    if (clean.length() > MAX_EMAIL_LENGTH) {
      throw new IllegalArgumentException("邮箱不能超过 " + MAX_EMAIL_LENGTH + " 个字符");
    }
    if (!EMAIL_PATTERN.matcher(clean).matches()) {
      throw new IllegalArgumentException("邮箱格式不正确: " + clean);
    }
    return clean;
  }

  private static void requirePassword(String login, String rawPassword) {
    List<PasswordPolicy.Violation> violations = PasswordPolicy.violations(login, rawPassword);
    if (!violations.isEmpty()) {
      throw ApiException.badRequest(
          "密码强度不足："
              + violations.stream().map(PasswordPolicy::describe).collect(Collectors.joining("，")));
    }
  }
}
