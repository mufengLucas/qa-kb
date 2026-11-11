package com.qa.kb.common;

import org.springframework.http.HttpStatus;

/** Application-level error carrying an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
  }

  public static ApiException unauthorized(String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, "conflict", message);
  }
}
