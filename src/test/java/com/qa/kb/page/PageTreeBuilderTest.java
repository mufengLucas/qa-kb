package com.qa.kb.page;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.kb.model.PageSummary;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PageTreeBuilder")
class PageTreeBuilderTest {

  private static PageSummary page(long id, Long parentId, String title, int sort) {
    return new PageSummary(id, parentId, title, "p" + id, sort);
  }

  @Test
  void emptyListProducesEmptyTree() {
    assertThat(PageTreeBuilder.build(List.of())).isEmpty();
  }

  @Test
  void rootsAreOrderedBySortThenId() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(
            List.of(page(3, null, "C", 2), page(1, null, "A", 1), page(2, null, "B", 1)));

    assertThat(tree).extracting(PageTreeBuilder.PageNode::title).containsExactly("A", "B", "C");
  }

  @Test
  void nestsChildrenUnderTheirParentAndSortsThem() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(
            List.of(
                page(1, null, "Root", 0),
                page(3, 1L, "Child B", 2),
                page(2, 1L, "Child A", 1),
                page(4, 2L, "Grandchild", 0)));

    assertThat(tree).hasSize(1);
    PageTreeBuilder.PageNode root = tree.get(0);
    assertThat(root.children())
        .extracting(PageTreeBuilder.PageNode::title)
        .containsExactly("Child A", "Child B");
    assertThat(root.children().get(0).children())
        .extracting(PageTreeBuilder.PageNode::title)
        .containsExactly("Grandchild");
  }

  @Test
  void orphanedPagesBecomeRoots() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(List.of(page(2, 999L, "Orphan", 0)));

    assertThat(tree).extracting(PageTreeBuilder.PageNode::title).containsExactly("Orphan");
  }

  @Test
  void selfParentedPageBecomesRoot() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(List.of(page(1, 1L, "Self", 0)));

    assertThat(tree).extracting(PageTreeBuilder.PageNode::title).containsExactly("Self");
  }

  @Test
  void cyclesAreBrokenInsteadOfRecursingForever() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(List.of(page(1, 2L, "A", 0), page(2, 1L, "B", 0)));

    assertThat(tree).extracting(PageTreeBuilder.PageNode::title).containsExactly("A", "B");
    assertThat(tree).allSatisfy(node -> assertThat(node.children()).isEmpty());
  }

  @Test
  void preservesSiblingOrderWithinEachDepth() {
    List<PageTreeBuilder.PageNode> tree =
        PageTreeBuilder.build(
            List.of(
                page(1, null, "Root", 0),
                page(4, 1L, "D", 4),
                page(2, 1L, "B", 2),
                page(3, 1L, "C", 3)));

    assertThat(tree.get(0).children())
        .extracting(PageTreeBuilder.PageNode::title)
        .containsExactly("B", "C", "D");
  }
}
