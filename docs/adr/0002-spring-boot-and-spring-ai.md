# ADR 0002 — Spring Boot 3.4+ with Spring AI for LLM integration

**Status:** Accepted (2026-07)

## Context

`atlas-core` needs a web framework, dependency injection, data access, and an abstraction over
LLM and embedding providers. Options considered for the AI layer: Spring AI, LangChain4j, or a
hand-rolled client over provider HTTP APIs.

## Decision

Spring Boot 3.4+ as the application framework. Spring AI as the LLM/embedding integration layer,
with OpenAI as the default provider (`gpt-4o-mini` class models for generation,
`text-embedding-3-small` for embeddings), configurable via application properties.

## Rationale

- **Spring Boot** is the default enterprise Java stack and the one Atlas's audience already runs.
  Boot 3.4+ specifically because Spring AI 1.x GA targets it; staying on the 3.3 line would have
  forced an upgrade mid-project under a working pipeline.
- **Spring AI over LangChain4j:** first-party Spring integration (auto-configuration, properties
  conventions, observability hooks), provider portability behind one `ChatClient`/
  `EmbeddingModel` API, and strategic alignment — an Atlas goal is demonstrating idiomatic
  Spring-native AI engineering. LangChain4j is a capable alternative; the deciding factor is
  ecosystem coherence, not capability.
- **Not hand-rolled:** provider APIs churn; the abstraction cost is already paid by Spring AI.

## Consequences

- Provider swapping is configuration-level for the *client* — but see ADR 0003: the embedding
  model choice is coupled to the database schema through the vector dimension, so "swappable"
  must not be overclaimed.
- Spring AI is younger than the rest of the stack; minor-version API movement is an accepted
  risk, mitigated by keeping Atlas's own code behind thin service interfaces
  (`GenerationService`, `EmbeddingService`) so churn is absorbed in one place.
- Blocking servlet stack (MVC + virtual threads), not WebFlux — see ADR 0001. SSE streaming for
  chat works fine on MVC.
