package com.orisenc.workflow;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated health endpoint, matching the platform-wide convention. */
@RestController
@RequestMapping("/public/health/workflow")
public class HealthController {

  @GetMapping
  public Map<String, Object> health() {
    return Map.of("service", "workflow", "status", "UP", "contract_version", "1.0.0");
  }
}
