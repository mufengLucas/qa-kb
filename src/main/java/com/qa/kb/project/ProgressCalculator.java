package com.qa.kb.project;

import java.time.LocalDate;
import java.util.Set;

/**
 * Fixed progress rules for the project page.
 *
 * <p>The plan pins exactly one formula, shown on the page: {@code 已完成任务数 / 总任务数}.
 */
public final class ProgressCalculator {

  public static final Set<String> TASK_STATUSES = Set.of("todo", "doing", "done", "blocked");

  private ProgressCalculator() {}

  /**
   * @param doneCount tasks in status {@code done}
   * @param totalCount all non-deleted tasks of the project
   */
  public static Progress of(int doneCount, int totalCount) {
    if (doneCount < 0 || totalCount < 0) {
      throw new IllegalArgumentException("done and total must be >= 0");
    }
    if (doneCount > totalCount) {
      throw new IllegalArgumentException("done must not exceed total");
    }
    if (totalCount == 0) {
      return new Progress(0, 0, 0);
    }
    int percent = (int) Math.round(doneCount * 100.0 / totalCount);
    return new Progress(doneCount, totalCount, Math.min(100, Math.max(0, percent)));
  }

  /** A task is overdue when it is not done and its due date is before {@code today}. */
  public static boolean isOverdue(String status, LocalDate dueDate, LocalDate today) {
    if (status == null || !TASK_STATUSES.contains(status)) {
      throw new IllegalArgumentException("unknown task status: " + status);
    }
    if (dueDate == null || "done".equals(status)) {
      return false;
    }
    if (today == null) {
      throw new IllegalArgumentException("today is required");
    }
    return dueDate.isBefore(today);
  }
}
