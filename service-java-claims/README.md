# Insurance Claims Tracker — Spring Boot Service

A simple REST API for managing insurance claims, backed by PostgreSQL. Demonstrates OpenChoreo secret references for database credentials and multi-environment promotion using PostgreSQL schemas.

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | /claims | List all claims |
| GET | /claims/{id} | Get a claim by ID |
| POST | /claims | Submit a new claim |
| PUT | /claims/{id} | Update a claim |
| PATCH | /claims/{id}/status?status= | Update claim status (`PENDING`, `APPROVED`, `REJECTED`) |
| DELETE | /claims/{id} | Delete a claim |

### Example requests

```bash
# Submit a claim
curl -X POST http://localhost:8080/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-001","claimantName":"Jane Doe","description":"Car accident","amount":2500.00}'

# List all claims
curl http://localhost:8080/claims

# Update a claim
curl -X PUT http://localhost:8080/claims/1 \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-001","claimantName":"Jane Doe","description":"Updated description","amount":3000.00}'

# Approve a claim
curl -X PATCH "http://localhost:8080/claims/1/status?status=APPROVED"
```

## Configuration

| Env Var | Description | Default (dev) |
|---------|-------------|---------------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL with schema | `jdbc:postgresql://claims-postgres:5432/claimsdb?currentSchema=dev` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `claims` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | *(from secret)* |
| `SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA` | Active schema | `dev` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Schema auto-creation | `update` |
| `SERVER_PORT` | HTTP port | `8080` |

## Local Development

Copy `.env.example` to `.env` and set your local DB password:

```bash
cp .env.example .env
# edit .env and set DB_PASSWORD
```

Then start the app and Postgres together:

```bash
docker compose up --build
```

The `.env` file is git-ignored and never committed.

## Project Structure

```
service-java-claims/
  src/                  Spring Boot application source
  db/                   PostgreSQL component (init.sql creates dev/staging/prod schemas)
  webapp/               Nginx-based UI for managing claims
  workload.yaml         OpenChoreo runtime descriptor (dev defaults)
  Dockerfile            Multi-stage build
```

## Deploying on OpenChoreo

This sample uses three PostgreSQL schemas (`dev`, `staging`, `prod`) within a single database to demonstrate environment promotion. Each environment uses its own schema and its own secret.

### 1. Store secrets in OpenBao

One secret per environment:

```bash
kubectl exec -n openbao openbao-0 -- sh -c '
  export BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=root
  bao kv put secret/claims/dev/db-password value=claims123
  bao kv put secret/claims/staging/db-password value=claims456
  bao kv put secret/claims/prod/db-password value=claims789
'
```

### 2. Create SecretReferences

One `SecretReference` per environment:

```bash
kubectl apply -f - <<'EOF'
apiVersion: openchoreo.dev/v1alpha1
kind: SecretReference
metadata:
  name: claims-db-secret-dev
  namespace: default
spec:
  template:
    type: Opaque
  data:
    - secretKey: db-password
      remoteRef:
        key: secret/claims/dev/db-password
        property: value
---
apiVersion: openchoreo.dev/v1alpha1
kind: SecretReference
metadata:
  name: claims-db-secret-staging
  namespace: default
spec:
  template:
    type: Opaque
  data:
    - secretKey: db-password
      remoteRef:
        key: secret/claims/staging/db-password
        property: value
---
apiVersion: openchoreo.dev/v1alpha1
kind: SecretReference
metadata:
  name: claims-db-secret-prod
  namespace: default
spec:
  template:
    type: Opaque
  data:
    - secretKey: db-password
      remoteRef:
        key: secret/claims/prod/db-password
        property: value
EOF
```

### 3. Create and deploy the components

Create three components in the same OpenChoreo project:

| Component | App Path | Description |
|-----------|----------|-------------|
| `claims-postgres` | `./service-java-claims/db` | PostgreSQL with dev/staging/prod schemas |
| `my-claims-app` | `./service-java-claims` | Spring Boot REST API |
| `claims-webapp` | `./service-java-claims/webapp` | Nginx UI |

Always deploy in this order: `claims-postgres` → `my-claims-app` → `claims-webapp`. The DB must be up and schemas created before the app starts, and the app must be up before the webapp can proxy to it.

### 4. Promoting to staging or production

Both `claims-postgres` and `my-claims-app` use the same secret for the database password — this guarantees they always stay in sync across environments.

When promoting each component through the OpenChoreo UI, set these overrides at the **Configure and Deploy** step:

#### `claims-postgres`

| Env Var | Staging value | Production value |
|---------|---------------|------------------|
| `POSTGRES_PASSWORD` (secretKeyRef name) | `claims-db-secret-staging` | `claims-db-secret-prod` |

#### `my-claims-app`

| Env Var | Staging value | Production value |
|---------|---------------|------------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://claims-postgres:5432/claimsdb?currentSchema=staging` | `jdbc:postgresql://<external-db>:5432/claimsdb?currentSchema=prod` |
| `SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA` | `staging` | `prod` |
| `SPRING_DATASOURCE_PASSWORD` (secretKeyRef name) | `claims-db-secret-staging` | `claims-db-secret-prod` |

#### `claims-webapp`

No overrides needed — Nginx proxies to `my-claims-app` by service name, which resolves correctly within the same project in any environment.

The overrides are saved on the ReleaseBinding and persist across future promotions — you only need to set them once per environment.
