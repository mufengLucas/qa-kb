package com.qa.kb.page;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import com.qa.kb.model.RevisionRow;
import com.qa.kb.model.TagCount;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.RevisionMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.TagMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/** Wiki pages: CRUD, soft delete/restore, revisions, tags and tree assembly. */
public class PageService {

  public static final int MAX_TITLE_LENGTH = 200;
  public static final int MAX_TAGS = 20;
  public static final int MAX_TAG_LENGTH = 50;
  private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED");

  private final PageMapper pageMapper;
  private final RevisionMapper revisionMapper;
  private final SpaceMapper spaceMapper;
  private final TagMapper tagMapper;
  private final Clock clock;

  public PageService(
      PageMapper pageMapper,
      RevisionMapper revisionMapper,
      SpaceMapper spaceMapper,
      TagMapper tagMapper,
      Clock clock) {
    this.pageMapper = pageMapper;
    this.revisionMapper = revisionMapper;
    this.spaceMapper = spaceMapper;
    this.tagMapper = tagMapper;
    this.clock = clock;
  }

  @Transactional
  public PageRow create(
      long spaceId,
      Long parentId,
      String title,
      String contentMd,
      String status,
      long authorId) {
    String cleanTitle = requireTitle(title);
    String cleanStatus = normalizeStatus(status);
    String content = contentMd == null ? "" : contentMd;

    spaceMapper.findById(spaceId).orElseThrow(() -> ApiException.notFound("空间不存在"));

    if (parentId != null) {
      PageRow parent =
          pageMapper.findById(parentId).orElseThrow(() -> ApiException.notFound("父页面不存在"));
      if (parent.spaceId() != spaceId) {
        throw ApiException.badRequest("父页面必须属于同一空间");
      }
    }

    pageMapper.insert(
        spaceId,
        parentId,
        cleanTitle,
        Slugifier.slugify(cleanTitle),
        content,
        cleanStatus,
        authorId,
        0);
    long id = pageMapper.lastInsertId();
    revisionMapper.insert(id, cleanTitle, content, authorId);
    return get(id);
  }

  @Transactional
  public PageRow update(long id, String title, String contentMd, String status, long editorId) {
    requirePage(id);
    String cleanTitle = requireTitle(title);
    String cleanStatus = normalizeStatus(status);
    String content = contentMd == null ? "" : contentMd;

    pageMapper.update(
        id,
        cleanTitle,
        Slugifier.slugify(cleanTitle),
        content,
        cleanStatus,
        LocalDateTime.now(clock));
    revisionMapper.insert(id, cleanTitle, content, editorId);
    return get(id);
  }

  public PageRow get(long id) {
    return requirePage(id);
  }

  @Transactional
  public void softDelete(long id) {
    requirePage(id);
    pageMapper.softDelete(id, LocalDateTime.now(clock));
  }

  @Transactional
  public void restore(long id) {
    pageMapper.findAnyById(id).orElseThrow(() -> ApiException.notFound("页面不存在"));
    pageMapper.restore(id);
  }

  public List<PageRow> trash() {
    return pageMapper.listDeleted();
  }

  public List<PageTreeBuilder.PageNode> tree(long spaceId) {
    return PageTreeBuilder.build(pageMapper.listBySpace(spaceId));
  }

  public List<PageSummary> pages(long spaceId) {
    return pageMapper.listBySpace(spaceId);
  }

  public List<RevisionRow> revisions(long pageId) {
    requirePage(pageId);
    return revisionMapper.listByPage(pageId);
  }

  @Transactional
  public PageRow rollback(long pageId, long revisionId, long editorId) {
    PageRow page = requirePage(pageId);
    RevisionRow revision =
        revisionMapper.findById(revisionId).orElseThrow(() -> ApiException.notFound("版本不存在"));
    if (revision.pageId() != pageId) {
      throw ApiException.badRequest("版本不属于该页面");
    }

    pageMapper.update(
        pageId,
        revision.title(),
        Slugifier.slugify(revision.title()),
        revision.contentMd(),
        page.status(),
        LocalDateTime.now(clock));
    revisionMapper.insert(pageId, revision.title(), revision.contentMd(), editorId);
    return get(pageId);
  }

  @Transactional
  public void setTags(long pageId, List<String> tags) {
    requirePage(pageId);
    List<String> clean = normalizeTags(tags);

    tagMapper.detachAll(pageId);
    for (String name : clean) {
      tagMapper.upsert(name);
      tagMapper.attach(pageId, tagMapper.findIdByName(name));
    }
  }

  public List<TagCount> tagsOf(long pageId) {
    return tagMapper.listByPage(pageId);
  }

  public List<TagCount> allTags() {
    return tagMapper.listAll();
  }

  private PageRow requirePage(long id) {
    return pageMapper.findById(id).orElseThrow(() -> ApiException.notFound("页面不存在"));
  }

  private static String requireTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("标题不能为空");
    }
    String clean = title.strip();
    if (clean.length() > MAX_TITLE_LENGTH) {
      throw new IllegalArgumentException("标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
    }
    return clean;
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return "PUBLISHED";
    }
    String clean = status.strip().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知的页面状态: " + status);
    }
    return clean;
  }

  private static List<String> normalizeTags(List<String> tags) {
    if (tags == null) {
      return List.of();
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String raw : tags) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String name = raw.strip();
      if (name.length() > MAX_TAG_LENGTH) {
        throw new IllegalArgumentException("标签不能超过 " + MAX_TAG_LENGTH + " 个字符");
      }
      unique.add(name);
    }
    if (unique.size() > MAX_TAGS) {
      throw new IllegalArgumentException("标签数量不能超过 " + MAX_TAGS + " 个");
    }
    return List.copyOf(unique);
  }
}
