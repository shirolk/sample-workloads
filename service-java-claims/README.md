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

| Env Var / Mount | Description | Default (dev) |
|-----------------|-------------|---------------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL with schema | `jdbc:postgresql://claims-postgres:5432/claimsdb?currentSchema=dev` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `claims` |
| `DB_PASSWORD_FILE` (file mount) | Path to file containing DB password | `/secrets/db-password` *(from secret)* |
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

## Zero-Downtime Secret Rotation

The database password is mounted as a file (`/secrets/db-password`) rather than injected as an environment variable. This enables zero-downtime rotation using two standard Java/HikariCP mechanisms:

- **`java.nio.file.WatchService`** — a background daemon thread watches the `/secrets/` directory for file changes. When OpenChoreo's External Secrets Operator syncs a new value from OpenBao, the mounted file updates on disk and triggers the watcher.
- **`HikariPoolMXBean.softEvictConnections()`** — on a file change event, the new password is applied via `HikariConfigMXBean.setPassword()` and then `softEvictConnections()` is called. This marks existing connections for eviction — they are replaced as they are returned to the pool rather than being forcibly closed — so in-flight requests complete normally with zero interruption.

No Spring Cloud, no pod restart, no application framework magic — just Java NIO and HikariCP's built-in JMX API.

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

| Component | Type | App Path | Description |
|-----------|------|----------|-------------|
| `claims-postgres` | `service` | `./service-java-claims/db` | PostgreSQL with dev/staging/prod schemas |
| `claims-service` | `service` | `./service-java-claims` | Spring Boot REST API |
| `claims-webapp` | `web-application` | `./service-java-claims/webapp` | Nginx UI |

Always deploy in this order: `claims-postgres` → `claims-service` → `claims-webapp`. The DB must be up and schemas created before the app starts, and the app must be up before the webapp can proxy to it.

Once deployed, add sample claims to each environment to verify everything is working:

```bash
# Dev
curl -s -X POST http://development-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-001","claimantName":"Jane Doe","description":"Car accident on highway","amount":2500.00}'
curl -s -X POST http://development-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-002","claimantName":"John Smith","description":"Home water damage","amount":8750.00}'
curl -s -X POST http://development-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-003","claimantName":"Alice Johnson","description":"Medical expenses","amount":1200.00}'
curl -s -X POST http://development-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-004","claimantName":"Bob Williams","description":"Theft of vehicle","amount":15000.00}'
curl -s -X POST http://development-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"POL-005","claimantName":"Carol Brown","description":"Fire damage to property","amount":32000.00}'

# Staging
curl -s -X POST http://staging-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"STG-001","claimantName":"Emily Clark","description":"Flood damage to basement","amount":12500.00}'
curl -s -X POST http://staging-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"STG-002","claimantName":"Michael Lee","description":"Roof damage from storm","amount":6800.00}'
curl -s -X POST http://staging-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"STG-003","claimantName":"Priya Patel","description":"Motorcycle accident injuries","amount":3400.00}'
curl -s -X POST http://staging-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"STG-004","claimantName":"James Wilson","description":"Burst pipe commercial premises","amount":21000.00}'

# Production
curl -s -X POST http://production-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"PROD-001","claimantName":"Sarah Connor","description":"Vehicle collision on freeway","amount":18500.00}'
curl -s -X POST http://production-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"PROD-002","claimantName":"David Park","description":"Office equipment theft","amount":9200.00}'
curl -s -X POST http://production-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"PROD-003","claimantName":"Maria Santos","description":"Burst pipe water damage","amount":4350.00}'
```

### 4. Promoting to staging or production

Both `claims-postgres` and `claims-service` use the same secret for the database password — this guarantees they always stay in sync across environments.

When promoting each component through the OpenChoreo UI, set these overrides at the **Configure and Deploy** step:

#### `claims-postgres`

| Env Var | Staging value | Production value |
|---------|---------------|------------------|
| `POSTGRES_PASSWORD` (secretKeyRef name) | `claims-db-secret-staging` | `claims-db-secret-prod` |

#### `claims-service`

| Override | Staging value | Production value |
|----------|---------------|------------------|
| `SPRING_DATASOURCE_URL` (env) | `jdbc:postgresql://claims-postgres:5432/claimsdb?currentSchema=staging` | `jdbc:postgresql://<external-db>:5432/claimsdb?currentSchema=prod` |
| `SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA` (env) | `staging` | `prod` |
| `db-password` file mount (secretKeyRef name) | `claims-db-secret-staging` | `claims-db-secret-prod` |

#### `claims-webapp`

No overrides needed — Nginx proxies to `claims-service` by service name, which resolves correctly within the same project in any environment.

The overrides are saved on the ReleaseBinding and persist across future promotions — you only need to set them once per environment.

### 5. Testing secret rotation on production

Secret rotation is zero-downtime — no pod restart required. The app's `WatchService` detects the file change, runs `ALTER USER` on the database, then updates HikariCP and soft-evicts connections.

**Step 1** — Update the password in OpenBao:

```bash
kubectl exec -n openbao openbao-0 -- sh -c '
  export BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=root
  bao kv put secret/claims/prod/db-password value=<new-password>
'
```

**Step 2** — Wait ~15 seconds for ESO to sync the new value to the Kubernetes secret.

**Step 3** — Verify both secrets updated:

```bash
# Get the secret names first
kubectl get secret -n <prod-dataplane-namespace> | grep claims

# Decode both
kubectl get secret <postgres-secret> -n <prod-dataplane-namespace> \
  -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d

kubectl get secret <app-secret> -n <prod-dataplane-namespace> \
  -o jsonpath='{.data.db-password}' | base64 -d
```

Both should show the new password.

**Step 4** — Confirm the app is still serving with no restart:

```bash
curl -s http://production-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims
```

**Step 5** — Add a new claim to confirm writes work after rotation:

```bash
curl -s -X POST http://production-default.openchoreoapis.localhost:19080/claims-service-claims-api/claims \
  -H "Content-Type: application/json" \
  -d '{"policyNumber":"PROD-004","claimantName":"Test Rotation","description":"Post-rotation write test","amount":1000.00}'
```

A successful `201` response with the new claim ID confirms that the rotated credentials are active and the connection pool is healthy.
