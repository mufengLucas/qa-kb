package com.qa.kb.web;

import com.qa.kb.model.PageRow;
import com.qa.kb.model.PageSummary;
import com.qa.kb.model.RevisionRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.model.TagCount;
import com.qa.kb.page.PageService;
import com.qa.kb.page.PageTreeBuilder;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.space.SpaceService;
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

/** Spaces, pages, page tree, revisions and tags. */
@RestController
@RequestMapping("/api")
public class PageController {

  public record SpaceRequest(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 80) String slug,
      @Size(max = 32) String icon,
      @Size(max = 500) String description,
      Integer sort) {}

  public record PageCreateRequest(
      Long parentId,
      @NotBlank @Size(max = 200) String title,
      String contentMd,
      String status) {}

  public record PageUpdateRequest(
      @NotBlank @Size(max = 200) String title, String contentMd, String status) {}

  public record TagsRequest(List<String> tags) {}

  private final SpaceService spaceService;
  private final PageService pageService;

  public PageController(SpaceService spaceService, PageService pageService) {
    this.spaceService = spaceService;
    this.pageService = pageService;
  }

  // ---------- spaces ----------

  @GetMapping("/spaces")
  public List<SpaceRow> spaces() {
    return spaceService.list();
  }

  @GetMapping("/spaces/{id}")
  public SpaceRow space(@PathVariable long id) {
    return spaceService.get(id);
  }

  @PostMapping("/spaces")
  public ResponseEntity<SpaceRow> createSpace(@Valid @RequestBody SpaceRequest body) {
    SpaceRow created =
        spaceService.create(
            body.name(),
            body.slug(),
            body.icon(),
            body.description(),
            body.sort() == null ? 0 : body.sort());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/spaces/{id}")
  public SpaceRow updateSpace(@PathVariable long id, @Valid @RequestBody SpaceRequest body) {
    return spaceService.update(
        id,
        body.name(),
        body.icon(),
        body.description(),
        body.sort() == null ? 0 : body.sort());
  }

  @DeleteMapping("/spaces/{id}")
  public ResponseEntity<Void> deleteSpace(@PathVariable long id) {
    spaceService.delete(id);
    return ResponseEntity.noContent().build();
  }

  // ---------- page tree ----------

  @GetMapping("/spaces/{spaceId}/pages/tree")
  public List<PageTreeBuilder.PageNode> tree(@PathVariable long spaceId) {
    spaceService.get(spaceId);
    return pageService.tree(spaceId);
  }

  @GetMapping("/spaces/{spaceId}/pages")
  public List<PageSummary> pages(@PathVariable long spaceId) {
    spaceService.get(spaceId);
    return pageService.pages(spaceId);
  }

  // ---------- pages ----------

  @PostMapping("/spaces/{spaceId}/pages")
  public ResponseEntity<PageRow> createPage(
      @PathVariable long spaceId,
      @Valid @RequestBody PageCreateRequest body,
      Authentication authentication) {
    PageRow created =
        pageService.create(
            spaceId,
            body.parentId(),
            body.title(),
            body.contentMd(),
            body.status(),
            CurrentUser.require(authentication).id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/pages/{id}")
  public PageRow page(@PathVariable long id) {
    return pageService.get(id);
  }

  @PutMapping("/pages/{id}")
  public PageRow updatePage(
      @PathVariable long id,
      @Valid @RequestBody PageUpdateRequest body,
      Authentication authentication) {
    return pageService.update(
        id,
        body.title(),
        body.contentMd(),
        body.status(),
        CurrentUser.require(authentication).id());
  }

  @DeleteMapping("/pages/{id}")
  public ResponseEntity<Void> deletePage(@PathVariable long id) {
    pageService.softDelete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/pages/trash")
  public List<PageRow> trash() {
    return pageService.trash();
  }

  @PostMapping("/pages/{id}/restore")
  public ResponseEntity<Void> restore(@PathVariable long id) {
    pageService.restore(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/pages/{id}/revisions")
  public List<RevisionRow> revisions(@PathVariable long id) {
    return pageService.revisions(id);
  }

  @PostMapping("/pages/{id}/rollback/{revisionId}")
  public PageRow rollback(
      @PathVariable long id, @PathVariable long revisionId, Authentication authentication) {
    return pageService.rollback(id, revisionId, CurrentUser.require(authentication).id());
  }

  @GetMapping("/pages/{id}/tags")
  public List<TagCount> tags(@PathVariable long id) {
    pageService.get(id);
    return pageService.tagsOf(id);
  }

  @PutMapping("/pages/{id}/tags")
  public ResponseEntity<Void> setTags(
      @PathVariable long id, @RequestBody TagsRequest body) {
    pageService.setTags(id, body.tags());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/tags")
  public List<TagCount> allTags() {
    return pageService.allTags();
  }
}
