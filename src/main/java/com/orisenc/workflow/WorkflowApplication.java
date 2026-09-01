package com.orisenc.workflow;

import com.orisenc.security.EnableOrisencSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Workflow and Task Management microservice. */
@SpringBootApplication
@EnableOrisencSecurity
@EnableMethodSecurity
public class WorkflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkflowApplication.class, args);
  }
}
