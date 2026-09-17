package io.tickmeet.common;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  public ResponseEntity<?> problem(Problem p) {
    HttpHeaders h = new HttpHeaders();
    if (p.status == 429) h.set("Retry-After", "60");
    return new ResponseEntity<>(
        Api.map("success", false, "code", p.code, "errorMsg", p.getMessage(), "data", null),
        h,
        HttpStatus.valueOf(p.status));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    java.time.format.DateTimeParseException.class
  })
  public ResponseEntity<?> bad(Exception e) {
    return problem(new Problem(400, "INVALID_ARGUMENT", "请求参数格式错误"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> big(Exception e) {
    return problem(new Problem(413, "INVALID_ARGUMENT", "图片不能超过5MiB"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> error(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("Unhandled request failure", e);
    return problem(new Problem(503, "DEPENDENCY_UNAVAILABLE", "服务暂时不可用，请稍后重试"));
  }
}
