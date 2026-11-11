package com.qa.kb.page;

import com.qa.kb.model.PageSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flat page list to nested page tree. */
public final class PageTreeBuilder {

  public record PageNode(
      long id, Long parentId, String title, String slug, int sort, List<PageNode> children) {}

  private static final Comparator<MutableNode> ORDER =
      Comparator.comparingInt((MutableNode node) -> node.summary.sort())
          .thenComparingLong(node -> node.summary.id());

  private PageTreeBuilder() {}

  public static List<PageNode> build(List<PageSummary> pages) {
    if (pages == null) {
      throw new IllegalArgumentException("pages are required");
    }

    Map<Long, MutableNode> byId = new LinkedHashMap<>();
    for (PageSummary page : pages) {
      if (page == null || page.id() == null) {
        throw new IllegalArgumentException("page and page id are required");
      }
      byId.putIfAbsent(page.id(), new MutableNode(page));
    }

    List<MutableNode> roots = new ArrayList<>();
    for (MutableNode node : byId.values()) {
      Long parentId = node.summary.parentId();
      MutableNode parent = parentId == null ? null : byId.get(parentId);
      if (parent == null || parent == node || wouldCycle(node, parent, byId)) {
        roots.add(node);
      } else {
        parent.children.add(node);
      }
    }

    sortRecursively(roots);
    return roots.stream().map(MutableNode::freeze).toList();
  }

  /** Walks up from {@code parent}; true when adding {@code node} below it would create a cycle. */
  private static boolean wouldCycle(
      MutableNode node, MutableNode parent, Map<Long, MutableNode> byId) {
    MutableNode current = parent;
    while (current != null) {
      if (current == node) {
        return true;
      }
      Long nextId = current.summary.parentId();
      if (nextId == null) {
        return false;
      }
      MutableNode next = byId.get(nextId);
      if (next == current) {
        return false;
      }
      current = next;
    }
    return false;
  }

  private static void sortRecursively(List<MutableNode> nodes) {
    nodes.sort(ORDER);
    for (MutableNode node : nodes) {
      sortRecursively(node.children);
    }
  }

  private static final class MutableNode {
    private final PageSummary summary;
    private final List<MutableNode> children = new ArrayList<>();

    private MutableNode(PageSummary summary) {
      this.summary = summary;
    }

    private PageNode freeze() {
      return new PageNode(
          summary.id(),
          summary.parentId(),
          summary.title(),
          summary.slug(),
          summary.sort() == null ? 0 : summary.sort(),
          children.stream().map(MutableNode::freeze).toList());
    }
  }
}
