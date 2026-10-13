package com.orisenc.workflow.projecttask;
import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import jakarta.persistence.EntityManager; import java.time.Instant; import java.util.*;
@Service public class TaskTemplateService { private final EntityManager em; public TaskTemplateService(EntityManager em){this.em=em;}
 @Transactional(readOnly=true) public List<TaskTemplateResponse> list(){return em.createQuery("select t from TaskTemplateEntity t where t.active=true order by t.name",TaskTemplateEntity.class).getResultList().stream().map(this::map).toList();}
 @Transactional(readOnly=true) public TaskTemplateResponse get(Long id){return map(find(id));}
 @Transactional public TaskTemplateResponse create(TaskTemplateRequest r){var t=new TaskTemplateEntity(r.name(),r.description(),r.titlePattern(),r.checklist(),r.subtasks(),"system",Instant.now());em.persist(t);return map(t);}
 @Transactional public TaskTemplateResponse update(Long id,TaskTemplateRequest r){var t=find(id);t.revise(r.name(),r.description(),r.titlePattern(),r.checklist(),r.subtasks(),"system",Instant.now());return map(t);}
 @Transactional public TaskTemplateResponse activate(Long id,boolean on){var t=find(id);if(on)t.activate("system",Instant.now());else t.deactivate("system",Instant.now());return map(t);}
 private TaskTemplateEntity find(Long id){var t=em.find(TaskTemplateEntity.class,id);if(t==null)throw new IllegalArgumentException("Task template not found.");return t;}
 private TaskTemplateResponse map(TaskTemplateEntity t){return new TaskTemplateResponse(t.getId(),t.getName(),t.isActive(),t.getVersion(),t.getTitlePattern(),t.getChecklist().stream().map(TaskTemplateChecklistEntity::getTitle).toList(),t.getSubtasks().stream().map(TaskTemplateSubtaskEntity::getTitle).toList());}
}
