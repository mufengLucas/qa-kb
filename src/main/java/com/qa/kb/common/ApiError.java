package com.qa.kb.common;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> details) {

  public static ApiError of(String code, String message) {
    return new ApiError(code, message, null);
  }
}
