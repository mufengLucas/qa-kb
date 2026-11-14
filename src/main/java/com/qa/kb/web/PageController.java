package com.qa.kb.web;

import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import com.qa.kb.model.RevisionRow;
import com.qa.kb.model.SpaceMemberRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.model.TagCount;
import com.qa.kb.page.PageService;
import com.qa.kb.page.PageTreeBuilder;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.space.SpaceAccessService;
import com.qa.kb.space.SpaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Spaces, pages, page tree, revisions and tags.
 *
 * <p>P2 Phase 3 起每个端点都经过 {@link SpaceAccessService} 做空间级判定：受限空间非成员 403，
 * 有效角色按「成员记录优先、无记录回退全局角色」计算。
 */
@RestController
@RequestMapping("/api")
public class PageController {

  public record SpaceRequest(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 80) String slug,
      @Size(max = 32) String icon,
      @Size(max = 500) String description,
      Integer sort,
      /** 是否受限空间；null 视为 false（创建）/ 保持语义由控制器按现值兜底（更新）。 */
      Boolean restricted) {}

  public record PageCreateRequest(
      Long parentId,
      @NotBlank @Size(max = 200) String title,
      String contentMd,
      String status) {}

  public record PageUpdateRequest(
      @NotBlank @Size(max = 200) String title, String contentMd, String status) {}

  public record TagsRequest(List<String> tags) {}

  /** 空间成员新增/调整（upsert）：重复提交同一用户即改角色。 */
  public record SpaceMemberRequest(@NotNull Long userId, @NotBlank String role) {}

  private final SpaceService spaceService;
  private final PageService pageService;
  private final SpaceAccessService access;

  public PageController(
      SpaceService spaceService, PageService pageService, SpaceAccessService access) {
    this.spaceService = spaceService;
    this.pageService = pageService;
    this.access = access;
  }

  // ---------- spaces ----------

  @GetMapping("/spaces")
  public List<SpaceRow> spaces(Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return access.filterReadable(user, spaceService.list());
  }

  @GetMapping("/spaces/{id}")
  public SpaceRow space(@PathVariable long id, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    SpaceRow space = spaceService.get(id);
    access.checkRead(user, space);
    return space;
  }

  @PostMapping("/spaces")
  public ResponseEntity<SpaceRow> createSpace(
      @Valid @RequestBody SpaceRequest body, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    SpaceRow created =
        spaceService.create(
            body.name(),
            body.slug(),
            body.icon(),
            body.description(),
            body.sort() == null ? 0 : body.sort(),
            Boolean.TRUE.equals(body.restricted()),
            user.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/spaces/{id}")
  public SpaceRow updateSpace(
      @PathVariable long id,
      @Valid @RequestBody SpaceRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    SpaceRow space = spaceService.get(id);
    access.checkAdmin(user, space);
    boolean restricted =
        body.restricted() == null
            ? Boolean.TRUE.equals(space.restricted())
            : Boolean.TRUE.equals(body.restricted());
    return spaceService.update(
        id,
        body.name(),
        body.icon(),
        body.description(),
        body.sort() == null ? 0 : body.sort(),
        restricted);
  }

  @DeleteMapping("/spaces/{id}")
  public ResponseEntity<Void> deleteSpace(@PathVariable long id, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkAdmin(user, spaceService.get(id));
    spaceService.delete(id);
    return ResponseEntity.noContent().build();
  }

  // ---------- space members（P2 Phase 3） ----------

  @GetMapping("/spaces/{id}/members")
  public List<SpaceMemberRow> spaceMembers(
      @PathVariable long id, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkRead(user, spaceService.get(id));
    return spaceService.listMembers(id);
  }

  @PostMapping("/spaces/{id}/members")
  public ResponseEntity<SpaceMemberRow> upsertSpaceMember(
      @PathVariable long id,
      @Valid @RequestBody SpaceMemberRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkAdmin(user, spaceService.get(id));
    SpaceMemberRow member = spaceService.upsertMember(id, body.userId(), body.role());
    return ResponseEntity.status(HttpStatus.CREATED).body(member);
  }

  @DeleteMapping("/spaces/{id}/members/{userId}")
  public ResponseEntity<Void> removeSpaceMember(
      @PathVariable long id, @PathVariable long userId, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkAdmin(user, spaceService.get(id));
    spaceService.removeMember(id, userId);
    return ResponseEntity.noContent().build();
  }

  // ---------- page tree ----------

  @GetMapping("/spaces/{spaceId}/pages/tree")
  public List<PageTreeBuilder.PageNode> tree(
      @PathVariable long spaceId, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    SpaceRow space = spaceService.get(spaceId);
    access.checkRead(user, space);
    return pageService.tree(spaceId);
  }

  @GetMapping("/spaces/{spaceId}/pages")
  public List<PageSummary> pages(@PathVariable long spaceId, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    SpaceRow space = spaceService.get(spaceId);
    access.checkRead(user, space);
    return pageService.pages(spaceId);
  }

  // ---------- pages ----------

  @PostMapping("/spaces/{spaceId}/pages")
  public ResponseEntity<PageRow> createPage(
      @PathVariable long spaceId,
      @Valid @RequestBody PageCreateRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkWrite(user, spaceService.get(spaceId));
    PageRow created =
        pageService.create(
            spaceId,
            body.parentId(),
            body.title(),
            body.contentMd(),
            body.status(),
            user.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/pages/{id}")
  public PageRow page(@PathVariable long id, Authentication authentication) {
    access.checkPageRead(CurrentUser.require(authentication), id);
    return pageService.get(id);
  }

  @PutMapping("/pages/{id}")
  public PageRow updatePage(
      @PathVariable long id,
      @Valid @RequestBody PageUpdateRequest body,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkPageWrite(user, id);
    return pageService.update(
        id, body.title(), body.contentMd(), body.status(), user.id());
  }

  @DeleteMapping("/pages/{id}")
  public ResponseEntity<Void> deletePage(
      @PathVariable long id, Authentication authentication) {
    access.checkPageWrite(CurrentUser.require(authentication), id);
    pageService.softDelete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/pages/trash")
  public List<PageRow> trash(Authentication authentication) {
    return access.filterReadablePages(
        CurrentUser.require(authentication), pageService.trash());
  }

  @PostMapping("/pages/{id}/restore")
  public ResponseEntity<Void> restore(@PathVariable long id, Authentication authentication) {
    access.checkPageWrite(CurrentUser.require(authentication), id);
    pageService.restore(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/pages/{id}/revisions")
  public List<RevisionRow> revisions(@PathVariable long id, Authentication authentication) {
    access.checkPageRead(CurrentUser.require(authentication), id);
    return pageService.revisions(id);
  }

  @PostMapping("/pages/{id}/rollback/{revisionId}")
  public PageRow rollback(
      @PathVariable long id, @PathVariable long revisionId, Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    access.checkPageWrite(user, id);
    return pageService.rollback(id, revisionId, user.id());
  }

  @GetMapping("/pages/{id}/tags")
  public List<TagCount> tags(@PathVariable long id, Authentication authentication) {
    access.checkPageRead(CurrentUser.require(authentication), id);
    pageService.get(id);
    return pageService.tagsOf(id);
  }

  @PutMapping("/pages/{id}/tags")
  public ResponseEntity<Void> setTags(
      @PathVariable long id, @RequestBody TagsRequest body, Authentication authentication) {
    access.checkPageWrite(CurrentUser.require(authentication), id);
    pageService.setTags(id, body.tags());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/tags")
  public List<TagCount> allTags(Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    return pageService.allTags(access.visibleSpaceIds(user));
  }
}
