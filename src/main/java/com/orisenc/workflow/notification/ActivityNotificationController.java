package com.orisenc.workflow.notification;

import static com.orisenc.workflow.notification.ActivityNotificationDtos.*;

import com.orisenc.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(path = "/api/notifications", produces = "application/json")
public class ActivityNotificationController {

  private final ActivityNotificationService service;
  private final CurrentUserProvider currentUser;

  public ActivityNotificationController(ActivityNotificationService service,
      CurrentUserProvider currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  /** Called by the Sales daemon to schedule a notification when an activity is planned. */
  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void schedule(@RequestBody ScheduleRequest request) {
    service.schedule(request);
  }

  /** Called by the Sales daemon when an activity is completed or cancelled. */
  @DeleteMapping("/by-activity/{activityId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void cancel(@PathVariable Long activityId) {
    service.cancel(activityId);
  }

  /** The signed-in user's fired, undismissed notifications. */
  @GetMapping("/mine")
  @PreAuthorize("hasAuthority('platform.notification.view')")
  public List<NotificationResponse> mine() {
    return service.activeFor(currentUser.get().preferredUsername());
  }

  /** Marks one notification dismissed for the signed-in user. */
  @PostMapping("/{id}/dismiss")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('platform.notification.view')")
  public void dismiss(@PathVariable Long id) {
    service.dismiss(id, currentUser.get().preferredUsername());
  }
}
