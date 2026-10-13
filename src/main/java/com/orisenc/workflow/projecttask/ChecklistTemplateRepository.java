package com.orisenc.workflow.projecttask;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplateEntity, Long> {
  List<ChecklistTemplateEntity> findAllByActiveTrueOrderByNameAsc();
  List<ChecklistTemplateEntity> findAllByOrderByActiveDescNameAsc();
  @Query("select count(t) > 0 from ChecklistTemplateEntity t where t.active = true"
      + " and lower(t.name) = lower(cast(:name as String)) and (:id is null or t.id <> :id)")
  boolean activeNameExists(@Param("name") String name, @Param("id") Long excludingId);
}
