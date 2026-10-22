package com.orisenc.workflow.task;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * The context the approval-task JPA slice boots, for the same reason as {@code ProjectTaskJpaTestApplication}:
 * without it {@code @DataJpaTest} finds {@code WorkflowApplication}, whose security wiring needs a real tenant.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = TaskEntity.class)
class TaskJpaTestApplication {}
