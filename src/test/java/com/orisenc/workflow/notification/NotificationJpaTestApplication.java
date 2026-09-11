package com.orisenc.workflow.notification;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * The context the notification JPA slice boots.
 *
 * <p>Exists because {@code @DataJpaTest} searches upward for a {@code @SpringBootConfiguration} and
 * would otherwise find {@link com.orisenc.workflow.WorkflowApplication}, whose security wiring needs
 * a web tier and a real Entra tenant.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = ActivityNotificationEntity.class)
class NotificationJpaTestApplication {}
