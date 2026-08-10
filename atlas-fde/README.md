# atlas-fde

Forward-deployed engineering playbooks and templates for standing up Atlas at a customer site.

## Structure

```
demo-vertical.md   # the reference deployment: EU digital-regulation assistant (what/who/baseline/demo)
playbooks/         # step-by-step runbooks: onboarding, data ingestion, cutover, incident response
templates/         # reusable config/checklist templates for new deployments
```

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
