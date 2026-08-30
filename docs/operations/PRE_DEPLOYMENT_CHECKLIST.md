# Nyvra Service — Pre-Deployment Checklist

Repository: `nyvra-service`

## Objective

Complete and stabilize the backend application before deploying it to a shared development environment.

---

# 1. Application Functionality

- [ ] Complete all planned business functionality
- [ ] Complete all required REST APIs
- [ ] Finalize request DTOs
- [ ] Finalize response DTOs
- [ ] Implement request validation
- [ ] Implement business rules
- [ ] Handle edge cases
- [ ] Handle expected failure scenarios
- [ ] Remove temporary/mock implementations
- [ ] Review all `TODO` items
- [ ] Review all `FIXME` items

---

# 2. API Design

## API Structure

- [ ] Finalize API URL structure
- [ ] Decide API versioning strategy
- [ ] Use consistent HTTP methods
- [ ] Use appropriate HTTP status codes
- [ ] Standardize request/response formats
- [ ] Standardize pagination
- [ ] Standardize sorting
- [ ] Standardize filtering
- [ ] Standardize date/time formats
- [ ] Standardize enum representation

Recommended:

```text
/api/v1/...
```

---

# 3. Error Handling

Implement a global exception handling mechanism.

- [ ] Global exception handler
- [ ] Validation errors
- [ ] Authentication errors
- [ ] Authorization errors
- [ ] Resource-not-found errors
- [ ] Conflict errors
- [ ] Business exceptions
- [ ] Unexpected exceptions
- [ ] Database errors
- [ ] External service errors

Standardize the error response.

Example:

```json
{
  "timestamp": "2026-08-29T14:30:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Invalid request",
  "errors": [
    {
      "field": "name",
      "message": "Name is required"
    }
  ],
  "traceId": "abc123"
}
```

---

# 4. Database

## Schema

- [ ] Finalize database schema
- [ ] Review table relationships
- [ ] Add required constraints
- [ ] Add foreign keys
- [ ] Add unique constraints
- [ ] Add indexes
- [ ] Review query performance
- [ ] Review N+1 queries
- [ ] Review transaction boundaries

## Migrations

- [ ] Configure Flyway/Liquibase or chosen migration mechanism
- [ ] Ensure every schema change has a migration
- [ ] Remove manually-created schema dependencies
- [ ] Verify a fresh database can be initialized
- [ ] Verify migrations execute in the correct order
- [ ] Verify migration failure behavior

The application should be able to start against an empty database and create the required schema through migrations.

---

# 5. TimescaleDB

If TimescaleDB is used:

- [ ] Finalize hypertables
- [ ] Configure required extensions
- [ ] Configure retention policies if required
- [ ] Configure continuous aggregates if required
- [ ] Review indexes
- [ ] Test time-series queries
- [ ] Test data ingestion
- [ ] Test data retention

---

# 6. Transactions

Review every operation that modifies multiple pieces of data.

- [ ] Identify transactional operations
- [ ] Add `@Transactional` where appropriate
- [ ] Review transaction boundaries
- [ ] Verify rollback behavior
- [ ] Test partial failures
- [ ] Verify asynchronous operations don't incorrectly depend on open transactions

---

# 7. Redis

- [ ] Finalize Redis usage
- [ ] Define cache keys
- [ ] Define TTLs
- [ ] Define invalidation strategy
- [ ] Handle cache misses
- [ ] Handle Redis unavailable scenario
- [ ] Avoid storing unnecessary data
- [ ] Test Redis restart
- [ ] Verify application behavior when Redis is unavailable

---

# 8. RabbitMQ

- [ ] Finalize exchanges
- [ ] Finalize queues
- [ ] Finalize routing keys
- [ ] Finalize message formats
- [ ] Configure durable queues where required
- [ ] Configure persistent messages where required
- [ ] Configure retry strategy
- [ ] Configure dead-letter handling
- [ ] Handle duplicate messages
- [ ] Make consumers idempotent where required
- [ ] Handle consumer failures
- [ ] Test RabbitMQ restart

Document the message contracts.

---

# 9. MinIO / Object Storage

- [ ] Finalize bucket structure
- [ ] Define object naming strategy
- [ ] Configure upload handling
- [ ] Configure download handling
- [ ] Configure deletion behavior
- [ ] Configure content types
- [ ] Handle missing objects
- [ ] Handle storage failures
- [ ] Validate file sizes/types
- [ ] Test upload/download
- [ ] Test storage restart

---

# 10. Authentication

Finalize Keycloak integration.

- [ ] Configure JWT validation
- [ ] Configure issuer
- [ ] Configure audience if required
- [ ] Configure client ID
- [ ] Configure realm
- [ ] Handle expired tokens
- [ ] Handle invalid tokens
- [ ] Handle missing tokens
- [ ] Handle logout/session expiration

---

# 11. Authorization

- [ ] Define application roles
- [ ] Define permissions
- [ ] Implement endpoint authorization
- [ ] Implement business-level authorization
- [ ] Test authorized requests
- [ ] Test unauthorized requests
- [ ] Test forbidden requests
- [ ] Verify users cannot access another user's data where applicable

---

# 12. Configuration

Remove environment-specific values from source code.

Configuration should be externalized.

Examples:

```text
Database URL
Database credentials
Redis URL
RabbitMQ URL
MinIO URL
Keycloak URL
Application URLs
Feature flags
```

## Tasks

- [ ] Create `application-local.yml`
- [ ] Create `application-dev.yml`
- [ ] Define environment variables
- [ ] Create `.env.example`
- [ ] Remove hard-coded URLs
- [ ] Remove hard-coded credentials
- [ ] Remove environment-specific configuration from Java code

---

# 13. Secrets

Search the repository for:

```text
password
secret
token
apiKey
privateKey
credentials
```

- [ ] Remove hard-coded secrets
- [ ] Remove credentials from configuration files
- [ ] Add sensitive files to `.gitignore`
- [ ] Verify Git history doesn't contain active secrets
- [ ] Rotate any exposed credentials

Never commit:

```text
.env
database passwords
Keycloak admin passwords
private keys
API secrets
access tokens
```

---

# 14. Logging

- [ ] Configure appropriate log levels
- [ ] Configure structured logging if required
- [ ] Include request/trace ID
- [ ] Log important business failures
- [ ] Log external service failures
- [ ] Log unexpected exceptions
- [ ] Avoid excessive DEBUG logging in dev/prod
- [ ] Never log passwords
- [ ] Never log access tokens
- [ ] Never log secrets
- [ ] Review sensitive request/response logging

---

# 15. Health Checks

Configure Spring Boot Actuator.

At minimum:

```text
/actuator/health
```

Verify health of critical dependencies:

```text
Application
Database
Redis
RabbitMQ
```

- [ ] Configure health endpoint
- [ ] Configure readiness
- [ ] Configure liveness if required
- [ ] Verify dependency health checks
- [ ] Ensure sensitive actuator endpoints are not publicly exposed

---

# 16. API Documentation

- [ ] Configure OpenAPI
- [ ] Document all public APIs
- [ ] Document request parameters
- [ ] Document request bodies
- [ ] Document response bodies
- [ ] Document authentication
- [ ] Document error responses
- [ ] Document pagination
- [ ] Document examples

Verify Swagger/OpenAPI works locally.

---

# 17. Testing

## Unit Tests

- [ ] Business logic tests
- [ ] Service tests
- [ ] Utility tests
- [ ] Validation tests
- [ ] Error handling tests

## Integration Tests

- [ ] Database tests
- [ ] Repository tests
- [ ] Redis tests
- [ ] RabbitMQ tests
- [ ] MinIO tests
- [ ] Keycloak/security tests

## API Tests

- [ ] Successful requests
- [ ] Invalid requests
- [ ] Unauthorized requests
- [ ] Forbidden requests
- [ ] Not-found scenarios
- [ ] Conflict scenarios
- [ ] Server errors

---

# 18. Test From a Clean Environment

The backend should work from scratch.

Test:

```text
Empty environment
       ↓
Start infrastructure
       ↓
Create database
       ↓
Run migrations
       ↓
Start application
       ↓
Application becomes healthy
```

- [ ] Test with empty database
- [ ] Test migrations
- [ ] Test initial/reference data
- [ ] Test application startup
- [ ] Test application restart
- [ ] Test infrastructure restart

---

# 19. Performance Review

- [ ] Identify slow APIs
- [ ] Review database queries
- [ ] Review indexes
- [ ] Review unnecessary network calls
- [ ] Review Redis usage
- [ ] Review RabbitMQ consumers
- [ ] Review large object/file operations
- [ ] Review memory usage
- [ ] Review thread pools
- [ ] Review connection pools

---

# 20. Docker Readiness

Before deployment, the service must run correctly in Docker.

- [ ] Dockerfile finalized
- [ ] Multi-stage build where appropriate
- [ ] Runtime image minimized
- [ ] Non-root user
- [ ] Environment variables supported
- [ ] Health check configured
- [ ] JVM configuration reviewed
- [ ] Application starts correctly
- [ ] Application shuts down gracefully

Test:

```bash
docker build -t nyvra-service .
docker run nyvra-service
```

---

# 21. Local Docker Compose

The complete backend stack should run locally.

```text
nyvra-service
postgres/timescaledb
redis
rabbitmq
minio
keycloak
```

- [ ] All services start
- [ ] All services communicate
- [ ] Application becomes healthy
- [ ] Database migrations run
- [ ] Authentication works
- [ ] RabbitMQ works
- [ ] Redis works
- [ ] MinIO works
- [ ] Restart behavior works
- [ ] Data persists where required

---

# 22. Repository Cleanup

- [ ] Remove unused code
- [ ] Remove unused dependencies
- [ ] Remove debug code
- [ ] Remove temporary configuration
- [ ] Remove test credentials
- [ ] Review TODO/FIXME
- [ ] Update README
- [ ] Document local setup
- [ ] Document environment variables
- [ ] Document database setup
- [ ] Document external dependencies

---

# 23. Final Pre-Deployment Test

Run:

```bash
./mvnw clean verify
```

Then:

```bash
docker build -t nyvra-service .
```

Then run the complete stack with Docker Compose.

Verify:

- [ ] Application starts
- [ ] Health endpoint returns `UP`
- [ ] Authentication works
- [ ] APIs work
- [ ] Database works
- [ ] Redis works
- [ ] RabbitMQ works
- [ ] MinIO works
- [ ] Errors are handled correctly
- [ ] Logs are useful
- [ ] No secrets are exposed

---

# Definition of Done

Nyvra Service is ready for deployment when:

- [ ] Core functionality is complete
- [ ] API contracts are stable
- [ ] Database migrations work from a clean database
- [ ] Authentication and authorization are complete
- [ ] Redis/RabbitMQ/MinIO integrations are stable
- [ ] Configuration is environment-independent
- [ ] No secrets are committed
- [ ] Automated tests pass
- [ ] Docker image works
- [ ] Complete Docker Compose environment works
- [ ] Health checks work
- [ ] API documentation is available
- [ ] Logging is sufficient for debugging
- [ ] Application can recover from dependency restarts
- [ ] README/setup documentation is complete