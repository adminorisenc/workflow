# Orisenc Workflow Service

Independent Spring Boot 3.5.16 / Java 21 microservice for shared Workflow and Task Management
(`REQ-0022`). It owns the `workflow` PostgreSQL database and exposes the task contract under
`/workflow-service/ws/api/tasks`.

The structure follows the Spring `Sales` service: one Gradle application, the shared Orisenc
security starter, a dedicated PostgreSQL database, an independent port/context path and a public
health endpoint. Unlike the Sales scaffold, this service contains the extracted task domain.

## Local run

Create an empty database named `workflow`, then supply database and Entra values through environment
variables:

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `ORISENC_TENANT_ID`, `ORISENC_CLIENT_ID`
- `ORISENC_AUTHORIZATION_BASE_URL` when Common Platform is not at its local default

Run `./gradlew bootRun`. The defaults are:

- API base: `http://localhost:8087/workflow-service/ws`
- health: `GET /public/health/workflow`
- database: `workflow`
- authorization resolver: `http://localhost:8080/common-platform-service/ws`

## API

| Method | Path | Permission |
| --- | --- | --- |
| `GET` | `/api/tasks`, `/api/tasks/{id}` | `platform.task.view` |
| `POST` | `/api/tasks` | `platform.task.create` |
| `POST` | `/api/tasks/{id}/actions` | `platform.task.view`, then `platform.task.execute` or `platform.task.approve` |

The permission codes remain unchanged for compatibility with existing Common Platform RBAC grants.
Common Platform owns the permission catalogue; Workflow owns the task records and enforcement.

Hibernate creates `workflow_task` and `workflow_task_history` in this service's database. Production
must replace `ddl-auto=update` with versioned migrations and `validate` before go-live.
