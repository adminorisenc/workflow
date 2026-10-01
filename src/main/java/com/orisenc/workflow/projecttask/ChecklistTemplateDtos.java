package com.orisenc.workflow.projecttask;

import java.time.Instant;
import java.util.List;

record ChecklistTemplateItemInput(String title, Boolean required) {}
record ChecklistTemplateWriteRequest(String name, String description,
    List<ChecklistTemplateItemInput> items, Long expectedVersion) {}
record ChecklistTemplateItemResponse(Long id, int sequenceNo, String title, boolean required) {}
record ChecklistTemplateResponse(Long id, long version, String name, String description, boolean active,
    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
    List<ChecklistTemplateItemResponse> items) {}
