package com.orisenc.workflow.sla;

import com.orisenc.workflow.projecttask.ProjectTaskEntity;
import com.orisenc.workflow.task.TaskEntity;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * The context the SLA JPA slice boots.
 *
 * <p>Exists because {@code @DataJpaTest} searches upward for a {@code @SpringBootConfiguration} and
 * would otherwise find {@link com.orisenc.workflow.WorkflowApplication}, whose security wiring needs
 * a web tier and a real Entra tenant.
 *
 * <p>Scans both entity packages, because the SLA pass is the one thing in this service that reads
 * across both task families - and the point of the slice is to run its queries, not to mock them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {ProjectTaskEntity.class, TaskEntity.class})
class SlaJpaTestApplication {}
