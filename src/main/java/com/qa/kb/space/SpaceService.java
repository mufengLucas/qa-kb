package com.qa.kb.space;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.SpaceMemberRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.page.Slugifier;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.SpaceMemberMapper;
import com.qa.kb.persistence.UserMapper;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Wiki spaces. A space groups a page tree, e.g. "测试规范" or "接口文档". */
public class SpaceService {

  public static final int MAX_NAME_LENGTH = 100;
  public static final int MAX_DESCRIPTION_LENGTH = 500;
  public static final int MAX_ICON_LENGTH = 32;
  public static final int MAX_SLUG_LENGTH = 80;
  private static final Pattern SLUG_PATTERN =
      Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

  private final SpaceMapper spaceMapper;
  private final SpaceMemberMapper memberMapper;
  private final UserMapper userMapper;

  public SpaceService(SpaceMapper spaceMapper, SpaceMemberMapper memberMapper, UserMapper userMapper) {
    this.spaceMapper = spaceMapper;
    this.memberMapper = memberMapper;
    this.userMapper = userMapper;
  }

  public List<SpaceRow> list() {
    return spaceMapper.findAll();
  }

  public SpaceRow get(long id) {
    return spaceMapper.findById(id).orElseThrow(() -> ApiException.notFound("空间不存在"));
  }

  /**
   * 创建空间（P2 Phase 3 起支持 restricted 与创建者入组）。
   *
   * @param ownerId 创建者用户编号；非空时自动写入一条空间成员（role=ADMIN），保证受限空间至少有
   *     一个管理者、创建者不会把自己锁在门外。系统种子数据传 null（种子空间均为开放空间）。
   */
  @Transactional
  public SpaceRow create(
      String name, String slug, String icon, String description, int sort, boolean restricted,
      Long ownerId) {
    String cleanName = requireText(name, MAX_NAME_LENGTH, "空间名称");
    String cleanSlug = normalizeSlug(slug, cleanName);
    String cleanIcon = icon == null ? "" : icon.strip();
    String cleanDescription = description == null ? "" : description.strip();
    if (cleanIcon.length() > MAX_ICON_LENGTH) {
      throw new IllegalArgumentException("图标不能超过 " + MAX_ICON_LENGTH + " 个字符");
    }
    if (cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
    }

    if (spaceMapper.findBySlug(cleanSlug).isPresent()) {
      throw ApiException.conflict("空间标识已存在: " + cleanSlug);
    }

    long id;
    try {
      spaceMapper.insert(
          cleanName, cleanSlug, cleanIcon, cleanDescription, Math.max(0, sort), restricted);
      id = spaceMapper.lastInsertId();
    } catch (DuplicateKeyException duplicate) {
      throw ApiException.conflict("空间标识已存在: " + cleanSlug);
    }
    if (ownerId != null) {
      memberMapper.upsert(id, ownerId, Role.ADMIN.name());
    }
    return spaceMapper
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("空间不存在"));
  }

  @Transactional
  public SpaceRow update(
      long id, String name, String icon, String description, int sort, boolean restricted) {
    get(id);
    String cleanName = requireText(name, MAX_NAME_LENGTH, "空间名称");
    String cleanIcon = icon == null ? "" : icon.strip();
    String cleanDescription = description == null ? "" : description.strip();
    if (cleanIcon.length() > MAX_ICON_LENGTH) {
      throw new IllegalArgumentException("图标不能超过 " + MAX_ICON_LENGTH + " 个字符");
    }
    if (cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
      throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
    }
    spaceMapper.update(
        id, cleanName, cleanIcon, cleanDescription, Math.max(0, sort), restricted);
    return get(id);
  }

  @Transactional
  public void delete(long id) {
    get(id);
    // 顺序不可调换：先删页面再删空间行，规避 MySQL 级联与 SET NULL 互相干扰的 ERROR 1452
    // （含嵌套子页面的空间直接删空间行会 409，见 SpaceMapper.deletePagesBySpace 注释）。
    // 成员行同样先清（不单靠 ON DELETE CASCADE），保持「先清依赖再删本行」的一致风格。
    memberMapper.deleteBySpace(id);
    spaceMapper.deletePagesBySpace(id);
    spaceMapper.delete(id);
  }

  // ---------- 空间成员（P2 Phase 3） ----------

  /** 空间成员列表（JOIN users 补登录名/姓名；已删除用户不会出现在列表里）。 */
  public List<SpaceMemberRow> listMembers(long spaceId) {
    get(spaceId);
    return memberMapper.listBySpace(spaceId);
  }

  /**
   * 添加或调整空间成员（方言 upsert：已存在则改角色，重复 POST 不报错）。
   * 用户不存在 404；角色名非法 400（Role.parse → IllegalArgumentException）。
   */
  @Transactional
  public SpaceMemberRow upsertMember(long spaceId, long userId, String role) {
    get(spaceId);
    String cleanRole = Role.parse(role).name();
    if (userMapper.findById(userId).isEmpty()) {
      throw ApiException.notFound("用户不存在");
    }
    // 不做「先 insert 抓 DuplicateKeyException 再 update」：SQLite 约束错不被 Spring
    // 翻译成该异常，两条路径在双方言下不等价，统一交给 mapper XML 的方言 upsert。
    memberMapper.upsert(spaceId, userId, cleanRole);
    return memberMapper.listBySpace(spaceId).stream()
        .filter(member -> member.userId() == userId)
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("成员不存在"));
  }

  /** 移出空间；本就不是成员时 404。 */
  @Transactional
  public void removeMember(long spaceId, long userId) {
    get(spaceId);
    if (memberMapper.delete(spaceId, userId) == 0) {
      throw ApiException.notFound("该用户不是空间成员");
    }
  }

  private static String normalizeSlug(String slug, String fallbackTitle) {
    String clean =
        slug == null || slug.isBlank()
            ? Slugifier.slugify(fallbackTitle)
            : slug.strip().toLowerCase(java.util.Locale.ROOT);
    if (clean.length() > MAX_SLUG_LENGTH || !SLUG_PATTERN.matcher(clean).matches()) {
      throw new IllegalArgumentException("空间标识只能包含小写字母、数字和连字符");
    }
    return clean;
  }

  private static String requireText(String text, int max, String label) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    String clean = text.strip();
    if (clean.length() > max) {
      throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符");
    }
    return clean;
  }
}
