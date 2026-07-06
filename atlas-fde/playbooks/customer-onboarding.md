# Playbook: Customer Onboarding

## Prerequisites

- [ ] Customer environment access confirmed (network, credentials, data access)
- [ ] `atlas-core` deployment target decided (see `docker/README.md`)
- [ ] Source documents identified and access verified

## Steps

1. Stand up `atlas-core` + vector store using `docker/docker-compose.yml`.
2. Run initial ingestion against a representative document sample.
3. Run `atlas-evals` against the deployed instance to establish a baseline.
4. Review eval results with the customer stakeholder — sign-off gate before scaling ingestion.
5. Ingest full document set.
6. Re-run `atlas-evals`; compare against baseline.
7. Go-live.

## Rollback

- Ingestion is idempotent per source document ID — re-running is safe.
- If eval scores regress after a change, redeploy the last known-good `atlas-core` image.
