# Infrastructure

- `k8s/` — Kustomize base + production overlay (backend, dashboard, Postgres with pgvector, Redis,
  OpenTelemetry collector, ingress, HPA).
- `otel/` — OpenTelemetry collector configuration shared by docker-compose and Kubernetes.

Local development uses the root `docker-compose.yml`.
