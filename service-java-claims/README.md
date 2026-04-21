# Insurance Claims Tracker — Spring Boot Service

A simple REST API for managing insurance claims, backed by PostgreSQL. Demonstrates OpenChoreo secret references for database credentials.

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | /claims | List all claims |
| GET | /claims/{id} | Get a claim by ID |
| POST | /claims | Submit a new claim |
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

# Approve a claim
curl -X PATCH "http://localhost:8080/claims/1/status?status=APPROVED"
```

## Configuration

| Env Var | Description | Default |
|---------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/claimsdb` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `claims` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | *(from secret)* |
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

## Deploying on OpenChoreo

### 1. Store the secret in OpenBao

Add the database password to OpenBao:

```bash
kubectl exec -n openbao openbao-0 -- sh -c '
  export BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=root
  bao kv put secret/claims/db-password value=claims123
'
```

### 2. Create the SecretReference

Create a `SecretReference` CR that maps the OpenBao path to a secret key:

```yaml
apiVersion: openchoreo.dev/v1alpha1
kind: SecretReference
metadata:
  name: claims-db-secret
spec:
  template:
    type: Opaque
  data:
    - secretKey: db-password
      remoteRef:
        key: secret/claims/db-password
        property: value
```

### 3. Deploy the component

The `workload.yaml` references `claims-db-secret` for the DB password. OpenChoreo fetches the password from OpenBao and injects it as `SPRING_DATASOURCE_PASSWORD` at runtime — the password never appears in source code or config files.
