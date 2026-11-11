package com.qa.kb.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiError> handleApi(ApiException ex) {
    return ResponseEntity.status(ex.status()).body(ApiError.of(ex.code(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> details = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      details.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    return ResponseEntity.badRequest()
        .body(new ApiError("validation_failed", "请求参数校验失败", details));
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ApiError> handleBadInput(Exception ex) {
    String message =
        ex instanceof HttpMessageNotReadableException ? "请求体格式不正确" : ex.getMessage();
    return ResponseEntity.badRequest().body(ApiError.of("bad_request", message));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
    return ResponseEntity.badRequest()
        .body(ApiError.of("missing_parameter", "缺少参数: " + ex.getParameterName()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest()
        .body(ApiError.of("invalid_parameter", "参数格式不正确: " + ex.getName()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleDuplicate(DataIntegrityViolationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError.of("conflict", "数据冲突，请检查是否重复"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(ApiError.of("method_not_allowed", "不支持的请求方法"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError.of("not_found", "资源不存在"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
    log.error("Unhandled error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.of("internal_error", "服务器内部错误"));
  }
}
