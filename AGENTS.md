# qits-toggles — working notes

Read `README.md` first: it is the design. This file is the conventions on top of it. Grow it with
the code.

## The rule that shapes everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere. `./mvnw verify` is the gate. The store is postgres and costs no docker:
`service/src/test/.../testdb/EmbeddedPg` spawns zonky's real binaries as a child process. **Never
Testcontainers, never a Quarkus dev service.**

The one address that is an exception is the platform Maven repository (`<repositories>` in the root
pom), where the qits-* jars live. Their versions are pinned in the root pom, exactly, and moved by
qits-maintenance's bumps — never by hand (a first adoption is the exception: it gets no bump).

## Layout

- `domain/` — owns the `toggles` datasource and the Flyway lineage at `db/toggles/migration`
  (schema `toggles`: `toggle`, `toggle_rule`, `toggle_freeze`). `store/ToggleStore` is **plain
  JDBC** — no persistence unit, no entity: the reconcile, the reads (one repeatable-read snapshot
  per call) and the whole-list rules replace under a version. The first entity,
  if one ever comes, is marked for `CausationRowRules` (`CausedRow` or `@Uncaused`) and brings
  `EntityExposureTest`, `docs/entities.json` and `entities:`/`database:` in release.yml.
- `service/` — the deployable: `api/` the configuration doors (`TogglesResource`: list, read, the
  rules replace), the rule-list shape check (`RulesShape`), the wire records (`ToggleWire`, their
  native registration `ApiWireReflection`) and `Roles`; `declarations/` the DSL (`Toggle`),
  the declarations themselves (`Declared`, one `@Produces` field per toggle), their collection and
  check (`Declarations`) and the startup reconcile (`DeclarationReconcile`).

## Conventions

- **No anonymous door** under `/toggles/api`: the `authenticated` permission in
  application.properties covers the whole path. Health is outside it.
- **A toggle is never created or deleted by a door.** It exists by its declaration in code
  (`declarations/Declared`); the startup reconcile is the only writer of the `toggle` table's rows.
  A new slug gets one STATIC rule from its default; an existing slug keeps its rules (only its
  description and declared default follow the code); an undeclared slug is deleted with its rules
  and freezes (cascade). Never add a door or a job that inserts or deletes a `toggle` row.
- **Slugs** are `^[a-z][a-z0-9-]{1,62}$` (`Toggle.bool` refuses others) and unique
  (`Declarations.check`); `declarations/ToggleTest` checks every field of `Declared`, so a bad or
  duplicate slug fails the build. The smoke toggle `toggles-smoke` is declared on purpose and no
  pact uses it: its removal ticket is the retirement's live proof.
- **Rules are replaced whole** (`PUT /toggles/api/toggles/{slug}/rules`), under the version the
  writer read: 409 when it moved, 400 for a list `RulesShape` refuses (with the `position` of the
  rule it names) or an unknown slug. A refused replace changes nothing. Reads are open to every
  authenticated caller.
- **Who changes rules**: `qits:admin` and `qits:desk-admin`. `DeskAdminParityTest` holds: wherever
  `qits:admin-agent` is admitted, so is `qits:desk-admin`.
- **Reflection** (native): every type bound by Jackson on a door is registered for reflection
  (`@RegisterForReflection`), with a test that the list is complete.
- **`java.util.ServiceLoader`**: Quarkus switches GraalVM's service-loader feature off. The first
  code that loads a provider from `META-INF/services` adds
  `quarkus.native.auto-service-loader-registration=true` in the same commit.

## Generated files, and how to regenerate each

Every one of these compares by default and fails with a diff; `-Dgolden.update=true` rewrites.

| File | Written by |
|---|---|
| `golden-masters/**` | `contracts/GoldenMasterRecordingTest`, states in `contracts/ProviderStates` |
| `docs/openapi.yml` | `api/OpenApiSchemaExportTest` (always rewrites; the diff is the review) |

The release gate fails any operation of `docs/openapi.yml` that `golden-masters/index.json` does not
cover, so a new door joins `GoldenMasterRecordingTest.INTERACTIONS` in the same commit. The
configuration doors are recorded in the state `a toggle is declared, asked by an admin` (the smoke
toggle redeclared fresh at version 0, `Bearer contract-qits-admin` as a `qits:admin` person).
`ConsumerPactVerificationTest` verifies pinned consumer pacts (none yet — then flip
`ClasspathPactLoader.REQUIRED` and drop `@IgnoreNoPactsToVerify`).

## The suites' callers

Every `@QuarkusTest` runs with the OIDC tenant off (`%test.qits.auth.machine.required=false`); an
`Authorization` value means a caller only once `support/ContractCallers` grants it (`caller`,
`bearer`, `client`).

## The userflow catalogue

`stories/**IT` boot the PACKAGED service (`-DskipITs=false`) against `idp.MockIdp` with the OIDC
tenant on, and write their reports under `service/target/userstories/`. The java-service release
recipe requires that directory and publishes it as `@userflows/qits-toggles`.
