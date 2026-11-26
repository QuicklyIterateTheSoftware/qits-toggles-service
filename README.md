# qits-toggles

The platform's feature toggles (epic qits-2542), deployed as the application `qits-toggles` at
`https://toggles.qits.<domain>/toggles`.

- **Declared, never created.** A toggle exists only by its declaration in this repository's code
  and ships with its release. Nothing creates a toggle at run time, and a declaration removed from
  the code removes the toggle at the next startup.
- **Evaluated once per call chain.** `GET /toggles/api/toggle/{slug}/boolean` answers `true` or
  `false`. The first evaluation for (toggle, origin trace id) is frozen, so every hop of one call
  chain sees the same answer, whatever the rules say by then. A freeze is kept for one hour. The
  origin trace id and the on-behalf-of user come from the chain context of
  qits-integrations-quarkus-javalib (`X-Qits-Origin-Trace-Id`, `X-Qits-On-Behalf-Of`).
- **Rules.** An ordered rule list per toggle, the first match deciding: USER (the subject is in a
  set), TIME (now is at or after an instant), and the one STATIC rule, always last. Every rule
  carries the value it answers.
- **Configured from qits-landing's root Toggles section** by `qits:admin` and the delivery desk
  (`qits:desk-admin`). Every authenticated caller may evaluate and read.
- **Retired by GC.** A consumer of a toggle declares a consumer pact for its evaluation path; a
  toggle no deployed consumer's pact has used for about three days gets a maintenance ticket,
  "remove toggle `<slug>`".

## Access

Every door under `/toggles/api` takes a bearer: an anonymous request answers 401 before any door is
matched (`quarkus.http.auth.permission.authenticated`). Health is at `/toggles/q/health/ready`.

## Layout

- `domain/` — the `toggles` datasource and its Flyway lineage (`db/toggles/migration`, schema
  `toggles`), and the stores over it, plain JDBC.
- `service/` — the deployable: health and the API under `/toggles/api`.
- `golden-masters/` — the provider golden masters, published as
  `eu.wohlben.qits:qits-toggles-golden-masters` and `@qits/toggles-golden-masters`.
- `docs/openapi.yml` — the API document, published as `@apidocs/qits-toggles`.

## Building

`./mvnw verify` on a clone of this repository alone: the store is a real postgres spawned by the
suite (zonky), so no docker is needed. `./mvnw verify -DskipITs=false` also runs the userflow
catalogue against the packaged service.
