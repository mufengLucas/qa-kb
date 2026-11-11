package com.qa.kb.web;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "time", OffsetDateTime.now(ZONE).toString());
  }
}
