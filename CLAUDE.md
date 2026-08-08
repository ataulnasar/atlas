# CLAUDE.md — guardrails for AI-assisted development

Standing rules for any AI agent (Claude Code or similar) working in this repository. These are
hard constraints, not suggestions. They exist because an agent previously deleted the user's real
local secrets file (`docker/.env`) during a verification step.

## Local secrets — do not touch

1. **NEVER read, copy, move, modify, or delete `docker/.env` — or any untracked `.env` file.**
   These hold the user's real secrets (API keys, database passwords). They are gitignored and
   maintained by hand. Do not recreate them, do not "restore" them, do not `rm` them, do not read
   them to extract a value. If a task appears to require the OpenAI API key, the contents of a
   `.env`, or the running stack's environment, **STOP and ask the user to perform that step
   themselves** (e.g. "please run `docker compose up -d --build` yourself" or "please run the
   live smoke test with your key"). Do not work around it by pulling the value from a running
   container, a process listing, or anywhere else.

2. **Never print environment variable values.** Do not echo, log, cat, or otherwise surface the
   contents of environment variables — especially anything that could contain a secret (`*_API_KEY`,
   `*_PASSWORD`, `*_TOKEN`, `.env` contents). Redact if you must reference that a value exists.

3. **Stack rebuilds that need env changes are the user's to run.** Rebuilding or recreating the
   docker compose stack in a way that depends on `.env` (which requires reading or writing it) is
   the user's job. Ask them to run it and report back; do not reconstruct `.env` to do it yourself.

## Why

`docker/.env` is created by the user from `docker/.env.example` and never committed
(`docker/**/.env` is gitignored). Losing it means the user loses their real credentials and has to
re-enter them by hand. Treat every untracked `.env` as read-never, write-never, delete-never.
