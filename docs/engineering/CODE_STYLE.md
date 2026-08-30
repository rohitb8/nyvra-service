# CODE_STYLE.md — nyvra-backend

Java conventions for readable, consistent, maintainable code. This is descriptive of what's already in
`user/`, `config/`, and `common/exception/` — follow that shape for every new module rather than
inventing a new one. Money/time/ID/versioning rules are non-negotiable project rules, not style — they
live in the root `CLAUDE.md` and are only referenced here, not repeated.

Solo/small-team project: the goal is that any file reads the same regardless of who (or which session)
wrote it, so nothing needs a mental "translation" pass before it can be reviewed or extended.

---

## 1. Package structure

**Package by module, then by layer:** `com.rohit.nyvra.<module>.<dto|mapper|...>`. A module's own
layer files (controller, service, repository, entity) sit directly in the module package; only things
with more than one file per layer get a subpackage (e.g. `user/dto/UserProfileResponse.java`).

Every module has a `package-info.java` stating its one-sentence responsibility and pointing at its
`docs/product/DOMAIN_MODEL.md` section — see any existing module for the shape.

Shared technical code (not a Modulith module) lives under `config/` and `common/`, never inside a
domain module.

---

## 2. Dependency injection

Constructor injection only. Fields are `private final`. No `@Autowired` on fields, no setter injection.

```java
@Service
public class CurrentUserService {
    private final UserProfileRepository repository;

    public CurrentUserService(UserProfileRepository repository) {
        this.repository = repository;
    }
}
```

A single-constructor class needs no `@Autowired` annotation — Spring infers it.

---

## 3. Entities

- Protected no-arg constructor for JPA only, commented `// for JPA`.
- A real constructor for application use that sets every required field, including the generated `id`.
- Explicit getters. No Lombok `@Data`/`@Getter`/`@Setter`/`@Builder` on entities — see §7.
- Mutation only through named methods or explicit setters for fields that are genuinely mutable; never
  a blanket setter for every field.
- `@PrePersist`/`@PreUpdate` for `createdAt`/`updatedAt`, not application code setting them.
- A short Javadoc-style `NOTE:` comment for a known, tracked gap (e.g. a column that must become
  encrypted later) is preferred over silently shipping it or leaving a bare `// TODO`.

Reference: `user/UserProfile.java`.

---

## 4. DTOs

Java `record`, never a class, for request/response DTOs — never expose JPA entities over the API
(project-wide rule, restated here because it's the most common way to break this file's spirit).

A static factory (`UserProfileResponse.from(UserProfile profile)`) is enough while the mapping is
trivial. Once a module has more than a couple of DTOs or the mapping has real logic, switch to
MapStruct mappers in `<module>/mapper/` instead of hand-written factories — don't let ad hoc mapping
code accumulate.

Reference: `user/dto/UserProfileResponse.java`.

---

## 5. Controllers

Thin: HTTP ↔ DTO translation only. No business logic, no direct repository access.

- `@RestController`, `@RequestMapping("/api/v1/...")`.
- `@Tag(name = "...")` at the class level, `@SecurityRequirement(name = "keycloak")` unless the
  endpoint is genuinely public.
- `@Operation(summary = ..., description = ...)` on every endpoint — this is the OpenAPI contract the
  frontend generates a client from, not optional documentation.
- Ownership checks (a user can only reach their own data) happen here or in the service layer, never
  assumed away.

Reference: `user/UserController.java`.

---

## 6. Services

All calculation and business logic lives here, unit-tested against the relevant spec
(`docs/product/FINANCIAL_RULES.md` / `docs/product/HEALTH_SCORE_SPEC.md` where applicable).

- `@Transactional` explicit on every service method that writes; `@Transactional(readOnly = true)` on
  read-only ones — don't rely on the default.
- No `HttpServletRequest`, no DTO construction beyond what the method needs to return — that's the
  controller's job, not the service's.

---

## 7. Lombok

Lombok is on the classpath (paired with MapStruct) but unused so far in entities/DTOs — that's a
deliberate choice, not an oversight. Keep it that way:

- **Do** use `@Slf4j` for loggers once logging is added.
- **Don't** use `@Data`, `@Getter`/`@Setter`, `@Builder`, or `@AllArgsConstructor` on entities or DTOs.
  Entities keep hand-written constructors/getters so their invariants (what's required, what's
  immutable, what triggers a timestamp update) stay visible in the class itself. DTOs are records,
  which already give you equals/hashCode/toString/accessors for free — Lombok adds nothing there.

---

## 8. Javadoc and comments

Class-level Javadoc states *purpose* — what the class is responsible for and any invariant a caller
must know — not a restatement of the method signatures below it. Use `{@code}` for identifiers and
`{@link}` for cross-references.

```java
/**
 * Resolves the authenticated Keycloak subject to a {@link UserProfile}, provisioning one on first
 * request (just-in-time). This is the only place a {@code UserProfile} is created from a token.
 */
```

Inline comments explain *why*, not *what* — reserve them for a non-obvious rationale, an edge case, or
a pointer to the spec that justifies a number or a branch. Don't comment code that already reads
clearly on its own.

---

## 9. Naming

- `PascalCase` for types, `camelCase` for methods/fields/locals, `UPPER_SNAKE_CASE` for constants.
- Full domain words, no abbreviations: `keycloakSubject`, not `kcSub`; `accountId`, not `acctId`.
- Booleans read as a question or a state: `isActive`, `hasConsent`, not `active` as a bare adjective on
  its own if it could be ambiguous.
- Test methods describe behaviour, not mechanics: `modularityModelBuilds()`, not `test1()`.

---

## 10. Imports and formatting

- No wildcard imports, no unused imports.
- Import order: `java.*`/`jakarta.*` → `org.springframework.*` → other third-party → `com.rohit.nyvra.*`
  (standard IDE default grouping — every file in the repo already follows this).
- 4-space indentation, no tabs.
- ~120-character soft line limit; wrap method chains and long parameter lists one argument per line
  when they don't fit.
- One top-level type per file.

No formatter is wired into the Maven build yet. Until one is (Spotless + a standard Java formatter
would be the natural addition), match the formatting of the file you're editing rather than your IDE's
default — consistency beats any individual preference.

---

## 11. Nulls, Optional, validation

- Repository lookups that may not find a row return `Optional<T>` — never `null`.
- Public service/controller methods don't return `null`; use `Optional`, an empty collection, or throw.
- Required constructor arguments are validated with `Objects.requireNonNull(x, "message")` when the
  caller isn't already constrained by the type system (e.g. Bean Validation on a DTO).
- Request DTO constraints (`@NotNull`, `@Positive`, `@Size`, etc.) live on the DTO, not re-checked by
  hand in the controller or service.

---

## 12. Exceptions

Custom exceptions are thin `RuntimeException` subclasses in `common/exception/`, named for the
condition (`ResourceNotFoundException`, not `CustomException`). All translation to an HTTP response
happens centrally in `GlobalExceptionHandler` — a controller never builds an `ApiError` itself, and a
5xx handler never leaks the underlying exception message to the client.

Reference: `common/exception/GlobalExceptionHandler.java`.

---

## 13. Method size and structure

Prefer several small, single-purpose methods over one long procedural block — pull out a named private
static helper (`extractAuthorities`, `formatFieldError`) the moment a lambda or a block stops reading
as one idea. A method should be understandable without scrolling.

---

## 14. What's covered elsewhere

These are project-wide rules, not style choices — don't restate or relitigate them here:

- Money (`BigDecimal`, scale, currency), time (`Instant` vs `LocalDate`), IDs (UUID), API versioning,
  migration rules, secrets — root `CLAUDE.md` → "Non-negotiable rules".
- Schema/column conventions — `docs/engineering/DATABASE_DESIGN.md`.
- Financial calculations and the health score formula — `docs/product/FINANCIAL_RULES.md` /
  `docs/product/HEALTH_SCORE_SPEC.md`.
