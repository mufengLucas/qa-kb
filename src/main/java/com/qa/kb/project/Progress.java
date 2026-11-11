package com.qa.kb.project;

/** Immutable progress snapshot for a project. */
public record Progress(int done, int total, int percent) {

  public Progress {
    if (done < 0 || total < 0) {
      throw new IllegalArgumentException("done and total must be >= 0");
    }
    if (done > total) {
      throw new IllegalArgumentException("done must not exceed total");
    }
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be within 0..100");
    }
  }
}
