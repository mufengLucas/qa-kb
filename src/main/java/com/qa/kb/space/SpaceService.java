package com.qa.kb.space;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.page.Slugifier;
import com.qa.kb.persistence.SpaceMapper;
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

  public SpaceService(SpaceMapper spaceMapper) {
    this.spaceMapper = spaceMapper;
  }

  public List<SpaceRow> list() {
    return spaceMapper.findAll();
  }

  public SpaceRow get(long id) {
    return spaceMapper.findById(id).orElseThrow(() -> ApiException.notFound("空间不存在"));
  }

  @Transactional
  public SpaceRow create(String name, String slug, String icon, String description, int sort) {
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
      spaceMapper.insert(cleanName, cleanSlug, cleanIcon, cleanDescription, Math.max(0, sort));
      id = spaceMapper.lastInsertId();
    } catch (DuplicateKeyException duplicate) {
      throw ApiException.conflict("空间标识已存在: " + cleanSlug);
    }
    return spaceMapper
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("空间不存在"));
  }

  @Transactional
  public SpaceRow update(long id, String name, String icon, String description, int sort) {
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
    spaceMapper.update(id, cleanName, cleanIcon, cleanDescription, Math.max(0, sort));
    return get(id);
  }

  @Transactional
  public void delete(long id) {
    get(id);
    // 顺序不可调换：先删页面再删空间行，规避 MySQL 级联与 SET NULL 互相干扰的 ERROR 1452
    // （含嵌套子页面的空间直接删空间行会 409，见 SpaceMapper.deletePagesBySpace 注释）。
    spaceMapper.deletePagesBySpace(id);
    spaceMapper.delete(id);
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
