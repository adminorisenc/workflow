package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.delegation.DelegationEntity;
import com.orisenc.workflow.task.TaskEntity;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * The context the project-task JPA slice boots.
 *
 * <p>The same reason {@code DelegationJpaTestApplication} exists, and it cannot be reused: that one
 * is package-private to the delegation package, so {@code @DataJpaTest} searching upward from here
 * skips past it and finds {@link com.orisenc.workflow.WorkflowApplication}, whose security wiring
 * needs a web tier and a real Entra tenant.
 *
 * <p>Scans the delegation entities too. {@link ProjectTaskService} is constructed with a
 * {@code DelegationService} and asks it for cover on every read, so a schema without those tables
 * fails the moment a work item is loaded.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {ProjectTaskEntity.class, DelegationEntity.class, TaskEntity.class})
class ProjectTaskJpaTestApplication {}
