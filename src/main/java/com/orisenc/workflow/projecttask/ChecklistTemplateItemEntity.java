package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;

@Entity
@Table(name = "checklist_template_item", indexes =
    @Index(name = "idx_checklist_template_item_template", columnList = "template_id,sequence_no"))
public class ChecklistTemplateItemEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "template_id", nullable = false) private ChecklistTemplateEntity template;
  @Column(name = "sequence_no", nullable = false) private int sequenceNo;
  @Column(nullable = false, length = 200) private String title;
  @Column(name = "is_required", nullable = false) private boolean required;

  protected ChecklistTemplateItemEntity() {}
  ChecklistTemplateItemEntity(ChecklistTemplateEntity template, int sequenceNo, String title, boolean required) {
    this.template = template;
    this.sequenceNo = sequenceNo;
    if (title == null || title.isBlank()) throw new IllegalArgumentException("A checklist item title is required.");
    this.title = title.trim();
    if (this.title.length() > 200)
      throw new IllegalArgumentException("A checklist item title cannot be longer than 200 characters.");
    this.required = required;
  }
  public Long getId() { return id; }
  public int getSequenceNo() { return sequenceNo; }
  public String getTitle() { return title; }
  public boolean isRequired() { return required; }
}
