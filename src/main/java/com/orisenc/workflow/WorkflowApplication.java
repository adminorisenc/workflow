package com.orisenc.workflow;

import com.orisenc.security.EnableOrisencSecurity;
import com.orisenc.workflow.delegation.DelegationProperties;
import com.orisenc.workflow.integration.IntegrationClientProperties;
import com.orisenc.workflow.sla.SlaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Workflow and Task Management microservice.
 *
 * <p>{@link EnableMethodSecurity} is required for {@code @PreAuthorize} to be enforced - without it
 * those annotations are silently ignored and every endpoint fails open.
 *
 * <p>{@link EnableScheduling} drives the SLA pass and the delegation expiry pass. The SLA pass
 * raises nothing unless an operator has configured a route to the Integration Service, so a
 * deployment without one schedules a job that looks at nothing; delegation expiry closes windows
 * whether or not anybody can be told about it, because an authority that has lapsed has lapsed.
 */
@SpringBootApplication
@EnableOrisencSecurity
@EnableConfigurationProperties({SlaProperties.class, DelegationProperties.class,
    IntegrationClientProperties.class})
@EnableMethodSecurity
@EnableScheduling
public class WorkflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkflowApplication.class, args);
  }
}
