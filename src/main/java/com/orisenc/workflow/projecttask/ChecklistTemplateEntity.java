package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Entity
@Table(name = "checklist_template", uniqueConstraints =
    @UniqueConstraint(name = "uk_checklist_template_active_name", columnNames = "active_name_key"))
public class ChecklistTemplateEntity {
  public static final int MAX_ITEMS = 50;

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, length = 120) private String name;
  @Column(length = 500) private String description;
  @Column(nullable = false) private boolean active = true;
  @Column(name = "active_name_key", length = 120) private String activeNameKey;
  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_by", nullable = false, length = 120) private String updatedBy;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version private long version;

  @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sequenceNo ASC") private List<ChecklistTemplateItemEntity> items = new ArrayList<>();

  protected ChecklistTemplateEntity() {}

  ChecklistTemplateEntity(String name, String description, List<ChecklistTemplateItemInput> items,
      String actor, Instant time) {
    this.createdBy = actor;
    this.createdAt = time;
    revise(name, description, items, actor, time);
  }

  public void revise(String name, String description, List<ChecklistTemplateItemInput> supplied,
      String actor, Instant time) {
    this.name = required(name, "Template name", 120);
    this.description = optional(description, 500);
    if (supplied == null || supplied.isEmpty())
      throw new IllegalArgumentException("A checklist template needs at least one item.");
    // Fifty keeps task creation and the editor usable while still covering long operating procedures.
    if (supplied.size() > MAX_ITEMS)
      throw new IllegalArgumentException("A checklist template cannot contain more than " + MAX_ITEMS + " items.");
    items.clear();
    for (int i = 0; i < supplied.size(); i++) {
      var item = supplied.get(i);
      if (item == null) throw new IllegalArgumentException("A checklist item is required.");
      items.add(new ChecklistTemplateItemEntity(this, i + 1, item.title(),
          item.required() == null || item.required()));
    }
    updatedBy = required(actor, "Actor", 120);
    updatedAt = time;
    refreshActiveNameKey();
  }

  public void activate(String actor, Instant time) { active = true; touch(actor, time); refreshActiveNameKey(); }
  public void deactivate(String actor, Instant time) { active = false; touch(actor, time); refreshActiveNameKey(); }
  private void touch(String actor, Instant time) { updatedBy = required(actor, "Actor", 120); updatedAt = time; }
  private void refreshActiveNameKey() { activeNameKey = active ? name.toLowerCase(Locale.ROOT) : null; }

  private static String required(String value, String label, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    String result = value.trim();
    if (result.length() > max) throw new IllegalArgumentException(label + " cannot be longer than " + max + " characters.");
    return result;
  }
  private static String optional(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String result = value.trim();
    if (result.length() > max) throw new IllegalArgumentException("Description cannot be longer than " + max + " characters.");
    return result;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public boolean isActive() { return active; }
  public String getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public String getUpdatedBy() { return updatedBy; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getVersion() { return version; }
  public List<ChecklistTemplateItemEntity> getItems() { return List.copyOf(items); }
}
