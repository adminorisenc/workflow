package com.orisenc.workflow.activity;

import com.orisenc.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Records authenticated API write attempts before they can change business state. */
@RestController
@RequestMapping("/api/activity-logs")
public class ActivityLog implements HandlerInterceptor, WebMvcConfigurer {
  private static final String ENTRY_ID = ActivityLog.class.getName() + ".entryId";
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;
  private final CurrentUserProvider currentUser;

  public ActivityLog(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
    this.jdbc = jdbc;
    this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    this.currentUser = currentUser;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String method = request.getMethod();
    if (!List.of("POST", "PUT", "PATCH", "DELETE").contains(method)) return true;
    // The security filter has authenticated the request by the time MVC calls preHandle.
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) return true;
    var actor = currentUser.get();
    String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    if (route.equals("null")) route = request.getRequestURI();
    String resourceRef = resourceRef(request);
    String actorObjectId = actor.objectId();
    String actorName = actor.preferredUsername();
    String routeValue = route;
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(
          "insert into activity_log (actor_object_id, actor_name, http_method, route, resource_ref, occurred_at) "
              + "values (?, ?, ?, ?, ?, current_timestamp)", new String[] {"id"});
      statement.setString(1, actorObjectId);
      statement.setString(2, actorName);
      statement.setString(3, method);
      statement.setString(4, routeValue);
      statement.setString(5, resourceRef);
      return statement;
    }, key);
    if (key.getKey() == null) throw new IllegalStateException("Activity entry was not created");
    request.setAttribute(ENTRY_ID, key.getKey().longValue());
    return true;
  }

  private static String resourceRef(HttpServletRequest request) {
    Object values = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(values instanceof Map<?, ?> variables)) return null;
    for (var entry : variables.entrySet()) {
      String key = String.valueOf(entry.getKey()).toLowerCase();
      if ((key.equals("id") || key.endsWith("id") || key.equals("key")) && entry.getValue() != null) {
        String value = String.valueOf(entry.getValue());
        return value.length() <= 200 ? value : null;
      }
    }
    return null;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
    Object id = request.getAttribute(ENTRY_ID);
    if (id instanceof Long entryId) {
      jdbc.update("update activity_log set status_code = ? where id = ?", error == null ? response.getStatus() : 500, entryId);
    }
  }

  public record Entry(long id, String actorObjectId, String actorName, String method,
      String route, String resourceRef, Integer statusCode, Instant occurredAt) {}
  public record Result(List<Entry> items, long total, int page, int size) {}

  @GetMapping
  @PreAuthorize("hasAuthority('platform.audit.view')")
  public Result list(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String actorObjectId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String route,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid activity page");
    }
    StringBuilder where = new StringBuilder(" where 1=1");
    MapSqlParameterSource parameters = new MapSqlParameterSource();
    if (actorObjectId != null && !actorObjectId.isBlank()) {
      where.append(" and actor_object_id = :actor");
      parameters.addValue("actor", actorObjectId);
    }
    if (method != null && !method.isBlank()) {
      where.append(" and http_method = :method");
      parameters.addValue("method", method.toUpperCase());
    }
    if (route != null && !route.isBlank()) {
      where.append(" and route = :route");
      parameters.addValue("route", route);
    }
    if (from != null) {
      where.append(" and occurred_at >= :from");
      parameters.addValue("from", Timestamp.from(from));
    }
    if (to != null) {
      where.append(" and occurred_at <= :to");
      parameters.addValue("to", Timestamp.from(to));
    }
    long total = namedJdbc.queryForObject("select count(*) from activity_log" + where, parameters, Long.class);
    parameters.addValue("limit", size).addValue("offset", page * size);
    List<Entry> items = namedJdbc.query(
        "select id, actor_object_id, actor_name, http_method, route, resource_ref, status_code, occurred_at "
            + "from activity_log" + where + " order by occurred_at desc, id desc limit :limit offset :offset",
        parameters, (rs, row) -> new Entry(rs.getLong("id"), rs.getString("actor_object_id"),
            rs.getString("actor_name"), rs.getString("http_method"), rs.getString("route"),
            rs.getString("resource_ref"), (Integer) rs.getObject("status_code"),
            rs.getTimestamp("occurred_at").toInstant()));
    return new Result(items, total, page, size);
  }
}
