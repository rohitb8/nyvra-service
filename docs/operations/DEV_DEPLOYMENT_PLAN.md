# Nyvra Service — Dev Deployment Plan

## Objective

Deploy `nyvra-service` and its required infrastructure into a shared development environment.

Repository:

`https://github.com/rohitb8/nyvra-service`

---

# 1. Target Architecture

```text
                    Internet
                       │
                       ▼
                  api.dev.nyvra.com
                       │
                       ▼
                  ┌──────────┐
                  │  Nginx   │
                  └────┬─────┘
                       │
                       ▼
                ┌──────────────┐
                │ nyvra-service│
                │ Spring Boot  │
                └──────┬───────┘
                       │
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
     PostgreSQL      Redis       RabbitMQ
     TimescaleDB
          │
          ├──────────────► MinIO
          │
          └──────────────► Keycloak
```

All infrastructure should communicate over a private Docker network.

---

# 2. Dev Server

## Tasks

- [ ] Create Ubuntu LTS VM
- [ ] Configure static/public IP
- [ ] Configure SSH access
- [ ] Create deployment user
- [ ] Configure firewall
- [ ] Install Docker
- [ ] Install Docker Compose
- [ ] Install Git

Recommended starting size:

```text
CPU: 2–4 cores
RAM: 8 GB
Disk: 50–100 GB SSD
```

---

# 3. DNS

Configure:

```text
api.dev.nyvra.com → DEV_SERVER_IP
```

## Tasks

- [ ] Create DNS A record
- [ ] Verify DNS resolution

---

# 4. Dockerfile

Verify the existing `Dockerfile`.

## Tasks

- [ ] Verify Java version
- [ ] Verify application build
- [ ] Verify runtime image
- [ ] Minimize final image
- [ ] Run as non-root user
- [ ] Configure JVM options
- [ ] Expose application port
- [ ] Add Docker health check

Build locally:

```bash
docker build -t nyvra-service .
```

Run locally:

```bash
docker run nyvra-service
```

---

# 5. Spring Boot Configuration

Use the existing environment/profile structure.

Recommended:

```text
local
dev
staging
prod
test
```

## Tasks

- [ ] Configure `dev` profile
- [ ] Remove hard-coded infrastructure URLs
- [ ] Configure environment variables
- [ ] Configure database URL
- [ ] Configure Redis URL
- [ ] Configure RabbitMQ URL
- [ ] Configure MinIO URL
- [ ] Configure Keycloak URL
- [ ] Configure CORS
- [ ] Configure logging
- [ ] Configure actuator/health endpoint

---

# 6. PostgreSQL / TimescaleDB

## Tasks

- [ ] Select TimescaleDB image/version
- [ ] Create PostgreSQL container
- [ ] Create persistent volume
- [ ] Create database
- [ ] Create database user
- [ ] Configure password through environment variable
- [ ] Enable required extensions
- [ ] Configure application connection
- [ ] Verify migrations
- [ ] Verify data persistence

Example internal connection:

```text
jdbc:postgresql://postgres:5432/nyvra
```

Do not expose PostgreSQL publicly.

---

# 7. Redis

## Tasks

- [ ] Add Redis container
- [ ] Configure version
- [ ] Configure authentication if required
- [ ] Configure persistence if required
- [ ] Configure Spring Boot connection
- [ ] Add health check
- [ ] Test connection

Internal hostname:

```text
redis
```

---

# 8. RabbitMQ

## Tasks

- [ ] Add RabbitMQ container
- [ ] Configure credentials
- [ ] Configure virtual host
- [ ] Configure exchanges
- [ ] Configure queues
- [ ] Configure persistent volume
- [ ] Configure Spring Boot connection
- [ ] Add health check
- [ ] Test producer/consumer

Internal hostname:

```text
rabbitmq
```

Do not expose RabbitMQ publicly unless required.

---

# 9. MinIO

## Tasks

- [ ] Add MinIO container
- [ ] Create persistent volume
- [ ] Configure access key
- [ ] Configure secret key
- [ ] Create required buckets
- [ ] Configure Spring Boot connection
- [ ] Test upload
- [ ] Test download
- [ ] Verify persistence

Internal hostname:

```text
minio
```

---

# 10. Keycloak

## Tasks

- [ ] Add Keycloak container
- [ ] Configure admin credentials
- [ ] Create persistent storage
- [ ] Create Nyvra realm
- [ ] Create Nyvra client
- [ ] Configure roles
- [ ] Configure users
- [ ] Configure redirect URLs
- [ ] Configure allowed origins
- [ ] Configure backend JWT validation
- [ ] Test authentication

Public URL:

```text
https://auth.dev.nyvra.com
```

---

# 11. Docker Compose

Create:

```text
docker-compose.dev.yml
```

Services:

```text
nyvra-service
postgres
redis
rabbitmq
minio
keycloak
```

Nginx may be included here or run separately.

## Tasks

- [ ] Define services
- [ ] Define private Docker network
- [ ] Define persistent volumes
- [ ] Define environment variables
- [ ] Define health checks
- [ ] Define service dependencies
- [ ] Configure restart policies
- [ ] Avoid exposing internal ports

---

# 12. Environment Variables

Create:

```text
.env.example
```

Document variables such as:

```text
SPRING_PROFILES_ACTIVE=dev

DATABASE_URL=
DATABASE_USERNAME=
DATABASE_PASSWORD=

REDIS_HOST=
REDIS_PASSWORD=

RABBITMQ_HOST=
RABBITMQ_USERNAME=
RABBITMQ_PASSWORD=

MINIO_ENDPOINT=
MINIO_ACCESS_KEY=
MINIO_SECRET_KEY=

KEYCLOAK_ISSUER_URI=
```

## Tasks

- [ ] Identify every required variable
- [ ] Remove secrets from source code
- [ ] Create `.env.example`
- [ ] Create server-side `.env`
- [ ] Add `.env` to `.gitignore`
- [ ] Verify secrets don't appear in logs

---

# 13. Health Checks

Expose:

```text
/actuator/health
```

## Tasks

- [ ] Enable health endpoint
- [ ] Configure Docker health check
- [ ] Verify database health
- [ ] Verify Redis health
- [ ] Verify RabbitMQ health
- [ ] Verify application health

Expected:

```text
GET https://api.dev.nyvra.com/actuator/health

200 OK
```

---

# 14. Nginx

Configure:

```text
api.dev.nyvra.com
        │
        ▼
      Nginx
        │
        ▼
nyvra-service:8080
```

## Tasks

- [ ] Configure reverse proxy
- [ ] Configure HTTPS
- [ ] Configure HTTP → HTTPS redirect
- [ ] Configure request limits
- [ ] Configure timeouts
- [ ] Configure logs
- [ ] Configure security headers
- [ ] Configure WebSocket support if required

---

# 15. HTTPS

## Tasks

- [ ] Configure Let's Encrypt
- [ ] Generate certificate
- [ ] Configure Nginx
- [ ] Enable HTTPS
- [ ] Redirect HTTP
- [ ] Configure automatic renewal
- [ ] Test renewal

---

# 16. Container Registry

Use GitHub Container Registry.

Image:

```text
ghcr.io/rohitb8/nyvra-service
```

## Tasks

- [ ] Configure GHCR
- [ ] Build image
- [ ] Push image
- [ ] Verify dev server can pull image
- [ ] Decide image tagging strategy

Recommended:

```text
dev-<git-sha>
```

Use immutable commit-based tags for deployments.

---

# 17. GitHub Actions

Create:

```text
.github/workflows/deploy-dev.yml
```

Pipeline:

```text
Push to main
     │
     ▼
Checkout
     │
     ▼
Setup Java
     │
     ▼
Run tests
     │
     ▼
Build application
     │
     ▼
Build Docker image
     │
     ▼
Push to GHCR
     │
     ▼
SSH to dev server
     │
     ▼
Pull new image
     │
     ▼
Restart service
     │
     ▼
Health check
```

## Tasks

- [ ] Create workflow
- [ ] Run Maven tests
- [ ] Build application
- [ ] Build Docker image
- [ ] Login to GHCR
- [ ] Push image
- [ ] Configure SSH deployment
- [ ] Pull image on server
- [ ] Restart container
- [ ] Run health check
- [ ] Fail deployment if health check fails

---

# 18. Deployment Script

Create on server:

```text
/opt/nyvra/deploy-service.sh
```

Responsibilities:

```text
Pull image
    ↓
Stop/update service
    ↓
Start service
    ↓
Wait for health
    ↓
Report success/failure
```

---

# 19. Rollback

Maintain the previous image version.

Example:

```text
Current:
nyvra-service:dev-a81d92

Previous:
nyvra-service:dev-719abc
```

## Tasks

- [ ] Store previous version
- [ ] Detect failed deployment
- [ ] Restore previous version
- [ ] Restart service
- [ ] Verify health
- [ ] Document rollback command

---

# 20. Database Backups

## Tasks

- [ ] Create PostgreSQL backup script
- [ ] Schedule daily backup
- [ ] Configure retention
- [ ] Store backups outside database container
- [ ] Test restore
- [ ] Document restore procedure

Minimum recommendation:

```text
Daily
7-day retention
```

---

# 21. Logging

## Tasks

- [ ] Configure Spring Boot logs
- [ ] Configure Docker log rotation
- [ ] Configure Nginx logs
- [ ] Ensure secrets are never logged
- [ ] Document log access

Useful commands:

```bash
docker compose logs nyvra-service
docker compose logs -f nyvra-service
```

---

# 22. Security

## Tasks

- [ ] Disable root SSH
- [ ] Disable password SSH
- [ ] Use SSH keys
- [ ] Configure firewall
- [ ] Keep Docker updated
- [ ] Keep Ubuntu updated
- [ ] Don't expose PostgreSQL
- [ ] Don't expose Redis
- [ ] Don't expose RabbitMQ
- [ ] Don't expose MinIO
- [ ] Secure Keycloak admin interface

Public ports should normally be:

```text
22
80
443
```

---

# 23. Testing

## Infrastructure

- [ ] PostgreSQL starts
- [ ] Redis starts
- [ ] RabbitMQ starts
- [ ] MinIO starts
- [ ] Keycloak starts
- [ ] All persistent volumes work

## Application

- [ ] Spring Boot starts
- [ ] Database connection works
- [ ] Redis connection works
- [ ] RabbitMQ connection works
- [ ] MinIO connection works
- [ ] Keycloak authentication works
- [ ] Health endpoint works

## External

- [ ] API DNS works
- [ ] HTTPS works
- [ ] API is reachable
- [ ] Authentication works
- [ ] Authenticated API request works

---

# 24. Definition of Done

- [ ] `https://api.dev.nyvra.com` is accessible
- [ ] HTTPS is enabled
- [ ] Spring Boot starts successfully
- [ ] PostgreSQL/TimescaleDB works
- [ ] Redis works
- [ ] RabbitMQ works
- [ ] MinIO works
- [ ] Keycloak works
- [ ] Health checks work
- [ ] Data survives container restart
- [ ] Secrets are not committed
- [ ] GitHub Actions deploys `main`
- [ ] Failed deployments are detected
- [ ] Rollback works
- [ ] Database backup/restore works