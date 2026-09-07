package com.orisenc.workflow.delegation;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import com.orisenc.workflow.projecttask.ProjectTaskEntity;
import com.orisenc.workflow.task.TaskEntity;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * The context the delegation JPA slice boots.
 *
 * <p>Exists because {@code @DataJpaTest} searches upward for a {@code @SpringBootConfiguration} and
 * would otherwise find {@link com.orisenc.workflow.WorkflowApplication}, whose security wiring needs
 * a web tier and a real Entra tenant.
 *
 * <p>Scans all three entity packages, not only this one: the point of {@code DelegatedActionTest} is
 * that a delegation changes what the task services do, and proving that needs both sides in the
 * same schema.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {DelegationEntity.class, ProjectTaskEntity.class, TaskEntity.class})
class DelegationJpaTestApplication {}
