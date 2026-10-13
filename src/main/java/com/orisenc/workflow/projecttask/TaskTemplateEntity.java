package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name="task_template", uniqueConstraints=@UniqueConstraint(name="uk_task_template_active_name", columnNames="active_name_key"))
public class TaskTemplateEntity {
  public static final int MAX_CHECKLIST=50, MAX_SUBTASKS=30;
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(nullable=false,length=120) private String name;
  @Column(length=500) private String description;
  @Column(nullable=false) private boolean active=true;
  @Column(name="active_name_key",length=120) private String activeNameKey;
  @Enumerated(EnumType.STRING) @Column(name="task_type",nullable=false,length=30) private ProjectTaskType taskType=ProjectTaskType.INTERNAL;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private TaskPriority priority=TaskPriority.MEDIUM;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ProjectTaskVisibility visibility=ProjectTaskVisibility.INDIVIDUAL;
  @Column(name="relevant_team",nullable=false,length=60) private String relevantTeam="General";
  @Column(name="title_pattern",nullable=false,length=200) private String titlePattern;
  @Column(name="default_description",length=4000) private String defaultDescription;
  @Column(name="default_summary",length=1000) private String defaultSummary;
  @Column(name="due_offset_days",nullable=false) private int dueOffsetDays;
  @Column(name="subtasks_mandatory",nullable=false) private boolean subtasksMandatory;
  @Column(name="created_by",nullable=false,length=120) private String createdBy;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  @Column(name="updated_by",nullable=false,length=120) private String updatedBy;
  @Column(name="updated_at",nullable=false) private Instant updatedAt;
  @Version private long version;
  @OneToMany(mappedBy="template",cascade=CascadeType.ALL,orphanRemoval=true) @OrderBy("sequenceNo ASC") private List<TaskTemplateChecklistEntity> checklist=new ArrayList<>();
  @OneToMany(mappedBy="template",cascade=CascadeType.ALL,orphanRemoval=true) @OrderBy("sequenceNo ASC") private List<TaskTemplateSubtaskEntity> subtasks=new ArrayList<>();
  protected TaskTemplateEntity() {}
  public TaskTemplateEntity(String name,String description,String pattern,List<String> checks,List<String> children,String actor,Instant now){createdBy=actor;createdAt=now;revise(name,description,pattern,checks,children,actor,now);}
  public void revise(String name,String description,String pattern,List<String> checks,List<String> children,String actor,Instant now){
    if(name==null||name.isBlank()||name.trim().length()>120) throw new IllegalArgumentException("Template name is required and must be at most 120 characters.");
    if(pattern==null||pattern.isBlank()) throw new IllegalArgumentException("A title pattern is required.");
    for(String token: pattern.split("\\{")) if(token.contains("}")&&!token.startsWith("reference}")&&!token.startsWith("customer}")) throw new IllegalArgumentException("Only {reference} and {customer} placeholders are allowed.");
    if(checks==null||checks.isEmpty()) throw new IllegalArgumentException("A task template needs checklist or subtasks.");
    if(checks.size()>MAX_CHECKLIST|| (children!=null&&children.size()>MAX_SUBTASKS)) throw new IllegalArgumentException("Task template limits are 50 checklist items and 30 subtasks.");
    this.name=name.trim();this.description=description;this.titlePattern=pattern.trim();this.checklist.clear();this.subtasks.clear();
    for(int i=0;i<checks.size();i++) this.checklist.add(new TaskTemplateChecklistEntity(this,i+1,checks.get(i),true));
    if(children!=null) for(int i=0;i<children.size();i++) this.subtasks.add(new TaskTemplateSubtaskEntity(this,i+1,children.get(i)));
    updatedBy=actor;updatedAt=now;activeNameKey=active?this.name.toLowerCase(Locale.ROOT):null;
  }
  public void activate(String a,Instant t){active=true;updatedBy=a;updatedAt=t;activeNameKey=name.toLowerCase(Locale.ROOT);} public void deactivate(String a,Instant t){active=false;updatedBy=a;updatedAt=t;activeNameKey=null;}
  public Long getId(){return id;} public String getName(){return name;} public boolean isActive(){return active;} public long getVersion(){return version;} public String getTitlePattern(){return titlePattern;} public List<TaskTemplateChecklistEntity> getChecklist(){return List.copyOf(checklist);} public List<TaskTemplateSubtaskEntity> getSubtasks(){return List.copyOf(subtasks);}
}
