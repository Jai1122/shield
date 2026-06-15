# Java / Spring Boot review rubric (judgment-level)

Linting, formatting, compilation, tests, and security scanning are handled by other tooling and
MUST NOT be commented on. Focus only on judgment-level concerns below.

## Layering & architecture
- Respect controller → service → repository layering. Controllers stay thin (no business logic,
  no direct repository access for non-trivial flows). Repositories return data, not orchestrate.
- Don't leak JPA entities across the web boundary — map to DTOs. Watch for entities serialized
  directly in `@RestController` responses.
- Keep cross-cutting concerns (logging, auth, tx) out of business methods where an aspect/filter
  is the right tool.

## Dependency injection
- Prefer constructor injection (final fields) over field `@Autowired`. Flag new field injection.
- Beware injecting `@Scope("prototype")`/request-scoped beans into singletons without a proxy.

## Transactions (high-value, frequently wrong)
- `@Transactional` on `public` methods only; private/protected/package methods are ignored by the
  proxy.
- **Self-invocation**: calling a `@Transactional` method via `this` from the same bean bypasses
  the proxy — the annotation has no effect. Flag this pattern.
- Don't put `@Transactional` on the controller. Keep transaction boundaries in the service layer.
- Read-only queries should use `@Transactional(readOnly = true)` where it matters.
- Avoid doing remote calls / long I/O inside a transaction (holds DB connections).

## JPA / persistence
- N+1: iterating a lazy association in a loop. Suggest fetch joins, `@EntityGraph`, or batch size.
- `LazyInitializationException` risk when accessing lazy fields outside a session/transaction.
- Don't call `save()` in a loop without batching; avoid `findAll()` on large tables.
- Equals/hashCode on entities using the database id can break for transient instances.

## Null-safety & Optional
- Don't return `null` collections — return empty. Don't call `.get()` on an `Optional` without a
  presence check; prefer `orElseThrow`/`map`/`ifPresent`.
- `Optional` is for return types, not fields or method parameters.

## Concurrency & state
- Spring singletons must be stateless/thread-safe. Flag mutable instance fields holding per-request
  state in singleton beans.
- Be careful with shared `SimpleDateFormat`, non-thread-safe utilities, and unguarded collections.

## API & validation
- Validate request bodies (`@Valid` + bean validation). Don't trust client input.
- Use proper HTTP status codes; centralize error handling in `@ControllerAdvice` rather than
  ad-hoc try/catch returning 200.
- Avoid breaking changes to public API contracts (field renames/removals) without versioning.

## Error handling
- Don't swallow exceptions (empty catch, catch-and-log-and-continue) where the caller needs to
  know. Don't catch `Exception`/`Throwable` broadly without reason.
- Don't expose stack traces or internal messages to clients.

## Design & duplication
- Flag obvious duplication visible in the diff or in the provided related-code snippets; suggest
  reuse of an existing utility/abstraction.
- Watch for primitive obsession, long methods, and leaking implementation details across modules.

## Configuration & resources
- No hard-coded environment-specific values (URLs, ports, credentials) — use config/properties.
- Close resources (try-with-resources). Don't create unbounded thread pools or executors per call.

When unsure, prefer fewer, high-confidence findings. False positives erode trust.
