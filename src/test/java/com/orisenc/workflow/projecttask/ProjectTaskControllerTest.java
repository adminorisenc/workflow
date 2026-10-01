package com.orisenc.workflow.projecttask;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orisenc.security.CurrentUser;
import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.api.ApiExceptionHandler;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.ProjectTaskDetail;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.TimeEntryRequest;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.TravelEntryRequest;
import com.orisenc.workflow.projecttask.ProjectTaskDtos.UpdateProjectTaskRequest;
import com.orisenc.workflow.task.TaskPriority;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP layer of the project work item API: routing, body binding, the permission gate and the
 * error contract.
 *
 * <p>The first test of its kind in this service - every other test calls the service directly, so
 * until now nothing exercised the annotations, the JSON binding or the status codes a screen has to
 * handle. Those are not incidental: {@code @PreAuthorize} is the only thing standing between a
 * permission and an endpoint, and a request body that binds one field short fails silently as a
 * null rather than loudly as an error.
 *
 * <p>Spring Boot's own security auto-configuration is excluded and method security enabled directly.
 * The shipped {@code orisenc-security} starter is applied by an explicit {@code @EnableOrisencSecurity}
 * rather than auto-configuration, so leaving it off costs this slice nothing and saves it needing a
 * real Entra tenant. Authorities are placed on the {@link SecurityContextHolder} exactly as the
 * service tests do, which keeps one way of saying "signed in as" across the suite.
 */
@WebMvcTest(controllers = ProjectTaskController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
// The controller is imported rather than found: the @SpringBootConfiguration this slice resolves to
// is ProjectTaskJpaTestApplication, which deliberately carries no @ComponentScan, so nothing in this
// package is registered by scanning.
@Import({ProjectTaskController.class, ApiExceptionHandler.class,
    ProjectTaskControllerTest.MethodSecurity.class})
class ProjectTaskControllerTest {

  @Configuration
  @EnableMethodSecurity
  static class MethodSecurity {}

  private static final String OWNER = "priya@orisenc.com";
  private static final String IMPOSTOR = "someone.else@orisenc.com";
  private static final Instant DUE = Instant.parse("2026-09-18T09:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

  @Autowired private MockMvc mvc;
  @MockitoBean private ProjectTaskService service;
  @MockitoBean private CurrentUserProvider currentUser;

  @BeforeEach
  void signIn() {
    when(currentUser.get()).thenReturn(new CurrentUser("tenant", "oid", "sub", "Priya", OWNER,
        "client", Set.of(), Set.of(), "user", false));
    authorities(ProjectTaskPermissions.VIEW, ProjectTaskPermissions.EXECUTE);
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  private void authorities(String... granted) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        OWNER, null, List.of(granted).stream().map(SimpleGrantedAuthority::new).toList()));
  }

  /** A detail the service can return; the controller only passes it through. */
  private static ProjectTaskDetail detail() {
    return new ProjectTaskDetail("WRK-1", 3L, "Deliver order", null, "Deliver and capture POD.",
        ProjectTaskType.DELIVERABLE, TaskPriority.HIGH, ProjectTaskStatus.IN_PROGRESS,
        ProjectTaskVisibility.RELEVANT_TEAM, "Operations", OWNER, OWNER, null,
        new ProjectTaskDtos.LinkedEntityResponse(LinkedEntityType.NONE, null, null),
        DUE, DUE, null, null, null, null, null, null, false, 0, List.of(), true, true, true, true, true,
        List.of(), List.of(), List.of(), List.of(), false, null, List.of(), true,
        new ProjectTaskDtos.EffortResponse(150, 0, BigDecimal.ZERO, "INR"), List.of(), List.of(), false, 0, 0, 0, true, List.of(), null);
  }

  private static String updateBody() {
    return """
        {"title":"Deliver order","summary":"Van booked.","description":"Deliver and capture POD.",
         "taskType":"DELIVERABLE","priority":"HIGH","visibility":"RELEVANT_TEAM",
         "relevantTeam":"Operations","linkedEntityType":"NONE","linkedEntityId":null,
         "linkedEntityRef":null,"parentTaskId":null,"dueAt":"2026-09-18T09:00:00Z",
         "plannedStartAt":"2026-09-17T04:30:00Z","plannedEndAt":null,"startedAt":null,
         "completedAt":null,"expectedVersion":3}
        """;
  }

  /* --------------------------------------------------------------------------- routing */

  @Test
  void putBindsTheWholeEditableStateAndReturnsTheUpdatedItem() throws Exception {
    when(service.update(eq("WRK-1"), any(), eq(OWNER), any())).thenReturn(detail());

    mvc.perform(put("/api/project-tasks/WRK-1").contentType(MediaType.APPLICATION_JSON)
            .content(updateBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("WRK-1"))
        .andExpect(jsonPath("$.mayEdit").value(true));

    var body = ArgumentCaptor.forClass(UpdateProjectTaskRequest.class);
    verify(service).update(eq("WRK-1"), body.capture(), eq(OWNER), any());
    // The fields worth proving bind: an Instant, a nullable Instant and the version guard. A body
    // that bound one short would arrive as a silent null rather than an error.
    assertThatRequest(body.getValue());
  }

  private static void assertThatRequest(UpdateProjectTaskRequest request) {
    org.assertj.core.api.Assertions.assertThat(request.summary()).isEqualTo("Van booked.");
    org.assertj.core.api.Assertions.assertThat(request.dueAt()).isEqualTo(DUE);
    org.assertj.core.api.Assertions.assertThat(request.plannedStartAt())
        .isEqualTo(Instant.parse("2026-09-17T04:30:00Z"));
    org.assertj.core.api.Assertions.assertThat(request.plannedEndAt()).isNull();
    org.assertj.core.api.Assertions.assertThat(request.expectedVersion()).isEqualTo(3L);
    org.assertj.core.api.Assertions.assertThat(request.priority()).isEqualTo(TaskPriority.HIGH);
  }

  @Test
  void postTimeBindsADateAndADuration() throws Exception {
    when(service.logTime(eq("WRK-1"), any(), eq(OWNER), any())).thenReturn(detail());

    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workDate\":\"2026-09-16\",\"durationMinutes\":150,\"note\":\"Delivered.\"}"))
        .andExpect(status().isOk());

    var body = ArgumentCaptor.forClass(TimeEntryRequest.class);
    verify(service).logTime(eq("WRK-1"), body.capture(), eq(OWNER), any());
    org.assertj.core.api.Assertions.assertThat(body.getValue().workDate()).isEqualTo(TODAY);
    org.assertj.core.api.Assertions.assertThat(body.getValue().durationMinutes()).isEqualTo(150);
  }

  @Test
  void postTravelBindsAMonetaryAmountWithoutLosingItsScale() throws Exception {
    when(service.logTravel(eq("WRK-1"), any(), eq(OWNER), any())).thenReturn(detail());

    mvc.perform(post("/api/project-tasks/WRK-1/travel").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"travelDate":"2026-09-16","fromLocation":"Depot","toLocation":"Site",
                 "purpose":"Delivery","travelMinutes":90,"expenseAmount":450.50,
                 "currencyCode":"INR","voucherRef":"BILL-22"}
                """))
        .andExpect(status().isOk());

    var body = ArgumentCaptor.forClass(TravelEntryRequest.class);
    verify(service).logTravel(eq("WRK-1"), body.capture(), eq(OWNER), any());
    // Bound as BigDecimal, so the two trailing digits survive the wire in the direction that matters
    // for what is stored - the display side is the client's problem.
    org.assertj.core.api.Assertions.assertThat(body.getValue().expenseAmount())
        .isEqualByComparingTo("450.50");
  }

  @Test
  void deleteRoutesCarryBothThePathIdAndTheEntryId() throws Exception {
    when(service.removeTime(eq("WRK-1"), eq(7L), eq(OWNER), any())).thenReturn(detail());
    when(service.removeTravel(eq("WRK-1"), eq(9L), eq(OWNER), any())).thenReturn(detail());

    mvc.perform(delete("/api/project-tasks/WRK-1/time/7")).andExpect(status().isOk());
    mvc.perform(delete("/api/project-tasks/WRK-1/travel/9")).andExpect(status().isOk());

    verify(service).removeTime(eq("WRK-1"), eq(7L), eq(OWNER), any());
    verify(service).removeTravel(eq("WRK-1"), eq(9L), eq(OWNER), any());
  }

  /* ------------------------------------------------------------------------- the actor */

  @Test
  void theActingIdentityComesFromTheTokenAndNeverFromTheBody() throws Exception {
    when(service.logTime(any(), any(), any(), any())).thenReturn(detail());

    // A body naming somebody else. The request has no field the controller reads for identity, and
    // this is what makes the history worth anything as an audit record.
    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + IMPOSTOR
                + "\",\"workDate\":\"2026-09-16\",\"durationMinutes\":60}"))
        .andExpect(status().isOk());

    // The actor is the token's identity; the body's username is carried through as data for the
    // service to authorize separately, which it refuses without platform.work.manage.
    verify(service).logTime(eq("WRK-1"), any(), eq(OWNER), any());
  }

  @Test
  void aRequestWithoutASignedInIdentityIsRefused() throws Exception {
    when(currentUser.get()).thenReturn(new CurrentUser("tenant", "oid", "sub", "Nobody", null,
        "client", Set.of(), Set.of(), "user", false));

    mvc.perform(delete("/api/project-tasks/WRK-1/time/7"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("A valid signed-in identity is required."));
  }

  /* --------------------------------------------------------------------- the permission gate */

  @Test
  void loggingEffortNeedsTheExecutePermissionAndNotMerelyView() throws Exception {
    authorities(ProjectTaskPermissions.VIEW);

    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workDate\":\"2026-09-16\",\"durationMinutes\":60}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Your role does not permit this action."));

    verify(service, org.mockito.Mockito.never()).logTime(any(), any(), any(), any());
  }

  @Test
  void editingNeedsOnlyViewAtTheDoor_becauseWhoMayEditIsARecordLevelDecision() throws Exception {
    authorities(ProjectTaskPermissions.VIEW);
    when(service.update(any(), any(), any(), any())).thenReturn(detail());

    // Deliberately open at this layer: whether this caller may edit this item depends on who owns
    // it and whether it is still open, which only the service can answer.
    mvc.perform(put("/api/project-tasks/WRK-1").contentType(MediaType.APPLICATION_JSON)
            .content(updateBody()))
        .andExpect(status().isOk());
  }

  /* ------------------------------------------------------------------------ the error contract */

  @Test
  void aServiceRefusalKeepsItsStatusAndItsMessage() throws Exception {
    when(service.update(any(), any(), any(), any()))
        .thenThrow(ApiException.conflict("This work item is closed. Reopen it before changing its details."));

    mvc.perform(put("/api/project-tasks/WRK-1").contentType(MediaType.APPLICATION_JSON)
            .content(updateBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message")
            .value("This work item is closed. Reopen it before changing its details."));
  }

  @Test
  void aMalformedBodyIsRejectedRatherThanBoundAsNulls() throws Exception {
    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workDate\":\"the sixteenth\",\"durationMinutes\":60}"))
        .andExpect(status().is4xxClientError());

    verify(service, org.mockito.Mockito.never()).logTime(any(), any(), any(), any());
  }

  /* --------------------------------------------------------------------- the correlation id */

  @Test
  void aSuppliedCorrelationIdIsCarriedThroughAndOneIsMintedWhenAbsent() throws Exception {
    when(service.logTime(any(), any(), any(), any())).thenReturn(detail());
    String body = "{\"workDate\":\"2026-09-16\",\"durationMinutes\":60}";

    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
        .header("X-Correlation-ID", "trace-42").content(body)).andExpect(status().isOk());
    verify(service).logTime(eq("WRK-1"), any(), eq(OWNER), eq("trace-42"));

    mvc.perform(post("/api/project-tasks/WRK-1/time").contentType(MediaType.APPLICATION_JSON)
        .content(body)).andExpect(status().isOk());
    var correlation = ArgumentCaptor.forClass(String.class);
    verify(service, org.mockito.Mockito.times(2))
        .logTime(eq("WRK-1"), any(), eq(OWNER), correlation.capture());
    // Never null and never empty: the audit row and the notification both key off it.
    org.assertj.core.api.Assertions.assertThat(correlation.getAllValues().getLast()).isNotBlank();
  }

  @Test void addsSubtasksAndBindsBatchWithVersion() throws Exception {
    when(service.addSubtasks(eq("WRK-1"), any(), eq(OWNER), any())).thenReturn(detail());
    mvc.perform(post("/api/project-tasks/WRK-1/subtasks").contentType(MediaType.APPLICATION_JSON)
        .content("{\"subtasks\":[{\"title\":\"Child\",\"priority\":\"MEDIUM\"}],\"expectedVersion\":3}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.mayAddSubtasks").value(true));
    var body = ArgumentCaptor.forClass(ProjectTaskDtos.AddSubtasksRequest.class);
    verify(service).addSubtasks(eq("WRK-1"), body.capture(), eq(OWNER), any());
    org.assertj.core.api.Assertions.assertThat(body.getValue().subtasks().getFirst().title()).isEqualTo("Child");
    org.assertj.core.api.Assertions.assertThat(body.getValue().expectedVersion()).isEqualTo(3L);
  }
  @Test void completionErrorNamesOpenSubtasksInResponseBody() throws Exception {
    when(service.transition(eq("WRK-1"), any(), eq(OWNER), any()))
        .thenThrow(ApiException.badRequest("2 subtasks are still open: WRK-A, WRK-B"));
    mvc.perform(post("/api/project-tasks/WRK-1/transition").contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"COMPLETED\",\"comment\":\"Done\",\"expectedVersion\":3}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("2 subtasks are still open: WRK-A, WRK-B"));
  }
  @Test void addingSubtasksRequiresViewPermission() throws Exception {
    authorities(ProjectTaskPermissions.EXECUTE);
    mvc.perform(post("/api/project-tasks/WRK-1/subtasks").contentType(MediaType.APPLICATION_JSON)
        .content("{\"subtasks\":[{\"title\":\"Child\"}],\"expectedVersion\":3}"))
        .andExpect(status().isForbidden());
  }
}
