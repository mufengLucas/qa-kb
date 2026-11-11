package com.qa.kb.web;

import com.qa.kb.model.SearchHit;
import com.qa.kb.search.SearchService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

  private final SearchService searchService;

  public SearchController(SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping("/search")
  public List<SearchHit> search(
      @RequestParam("q") String q,
      @RequestParam(required = false) Long spaceId,
      @RequestParam(required = false) Integer limit) {
    return searchService.search(q, spaceId, limit);
  }
}
