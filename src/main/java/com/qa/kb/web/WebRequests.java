package com.qa.kb.web;

import jakarta.servlet.http.HttpServletRequest;

/** Small HTTP helpers shared by controllers. */
final class WebRequests {

  private WebRequests() {}

  /** Real client IP, honouring the reverse proxy header but never trusting appended hops. */
  static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].strip();
    }
    return request.getRemoteAddr();
  }
}
