ATLAS_BASE_URL ?= http://localhost:8080

.PHONY: lint typecheck test smoke smoke-live check

lint:
	uv run ruff check .

typecheck:
	uv run mypy

test:
	uv run pytest

# Harness smoke: the full pipeline (run -> report -> compare) against an in-process
# mock Atlas. No OpenAI key, no running stack, no Docker. This is what CI runs.
smoke:
	uv run python scripts/ci_smoke.py

# Live smoke: the real 6-question mini-golden dataset against a running Atlas stack.
# Needs a running stack and (usually) ATLAS_API_KEY in your environment — NOT run in CI
# (requires keys + Docker + OpenAI spend). See README "CI smoke & live smoke".
#   ATLAS_API_KEY=... make smoke-live          # against http://localhost:8080
#   ATLAS_BASE_URL=http://host:8080 make smoke-live
smoke-live:
	uv run atlas-eval run --dataset mini-golden --base-url $(ATLAS_BASE_URL)

check: lint typecheck test smoke
