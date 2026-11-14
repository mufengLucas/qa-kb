package com.qa.kb.web;

import com.qa.kb.model.SearchHit;
import com.qa.kb.search.SearchService;
import com.qa.kb.security.CurrentUser;
import com.qa.kb.security.SessionUser;
import com.qa.kb.space.SpaceAccessService;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

  private final SearchService searchService;
  private final SpaceAccessService access;

  public SearchController(SearchService searchService, SpaceAccessService access) {
    this.searchService = searchService;
    this.access = access;
  }

  /**
   * 搜索（P2 Phase 3 起按空间权限过滤）：指定 spaceId 时先校验该空间的读权限；不指定时对结果
   * 按可见空间集合剪枝，受限空间的命中不会泄露给非成员。
   */
  @GetMapping("/search")
  public List<SearchHit> search(
      @RequestParam("q") String q,
      @RequestParam(required = false) Long spaceId,
      @RequestParam(required = false) Integer limit,
      Authentication authentication) {
    SessionUser user = CurrentUser.require(authentication);
    if (spaceId != null) {
      access.requireRead(user, spaceId);
      return searchService.search(q, spaceId, limit);
    }
    List<SearchHit> hits = searchService.search(q, spaceId, limit);
    Set<Long> visible = access.visibleSpaceIds(user);
    return hits.stream().filter(hit -> visible.contains(hit.spaceId())).toList();
  }
}
