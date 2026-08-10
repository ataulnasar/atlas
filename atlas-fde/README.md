# atlas-fde

Forward-deployed engineering playbooks and templates for standing up Atlas at a customer site.

## Structure

```
demo-vertical.md   # the reference deployment: EU digital-regulation assistant (what/who/baseline/demo)
runbook.md         # operating a prod deployment: install, upgrade, backup/restore, rollback, troubleshooting
playbooks/         # step-by-step runbooks: onboarding, data ingestion, cutover, incident response
templates/         # reusable config/checklist templates for new deployments
```

## Operating a deployment

[`runbook.md`](runbook.md) is the operator's guide for a production-profile deployment — install and
secrets (including the two-distinct-keys explanation), upgrades (Flyway runs on boot), a tested
backup/restore procedure, rollback across migration boundaries, a symptom→cause→fix troubleshooting
table drawn from real incidents, and monitoring basics. Its first diagnostic step is
`atlas-eval doctor`.

## Reference deployment

[`demo-vertical.md`](demo-vertical.md) positions the **EU digital-regulation knowledge assistant**
as Atlas's reference vertical — what it is, who it's for, the corpus, the measured baseline, a
demo script, and the three swaps (corpus + strip-patterns + golden dataset) that turn it into a
new customer vertical without touching `atlas-core`.

## Scope

This folder is documentation and configuration, not application code. It codifies the
repeatable process an FDE follows per engagement — prerequisites, ingestion checklist,
eval sign-off gate before go-live, and rollback steps.

## Status

Scaffold only — playbooks to be filled in as v1 deployments happen.
