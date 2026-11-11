package com.qa.kb.page;

import java.text.Normalizer;
import java.util.Locale;

/** Turns a page title into an URL-safe cosmetic slug. The numeric id remains authoritative. */
public final class Slugifier {

  public static final int MAX_LENGTH = 60;
  public static final String FALLBACK = "page";

  private Slugifier() {}

  public static String slugify(String title) {
    if (title == null) {
      return FALLBACK;
    }
    String ascii =
        Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");

    if (ascii.isEmpty()) {
      return FALLBACK;
    }
    if (ascii.length() > MAX_LENGTH) {
      ascii = ascii.substring(0, MAX_LENGTH).replaceAll("-+$", "");
    }
    return ascii;
  }

  /** Canonical page path: id first, slug only for readability. */
  public static String pagePath(long id, String title) {
    return "/pages/" + id + "/" + slugify(title);
  }
}
