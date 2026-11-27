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

## Evaluating a toggle

`GET /toggles/api/toggle/{slug}/boolean` (`getToggleBoolean`) answers a JSON `true` or `false` to
any bearer. An undeclared slug answers 400 naming it, and a type segment other than `boolean` has no
route.

1. **The chain** is the request's `ChainContext` (qits-integrations-quarkus-javalib): its origin
   trace id and whom it acts for. The library has already applied its rule: only a plain service
   client's `X-Qits-Origin-Trace-Id` and `X-Qits-On-Behalf-Of` count. A person or an agent always
   starts its own chain at its own trace id and speaks for its own bearer, whatever headers it sends.
2. **A frozen answer wins.** When (slug, origin trace id) has a freeze, that is the answer.
3. **Otherwise the rules are walked in order** (`RuleWalk`), with the subject = the chain's
   on-behalf-of user, else the caller's own `sub`.
4. **The answer is frozen** with `insert … on conflict do nothing`, and the row that stands is
   answered, so two replicas racing on one chain agree.

`FreezePurge` drops freezes older than `qits.toggles.freeze-ttl` (1h) every minute, on every
replica; the delete is idempotent.

**A consumer** calls it like any peer, through a small REST-client interface with its own service
bearer. The integrations library stamps both chain headers on every outgoing call, so no call site
sets them. The consumer declares a pact on its toggle's concrete path, which is how the retirement
run knows the toggle is still used. Golden-master state: `toggle <slug> is declared`, param `slug`.

## Retiring a toggle

`POST /toggles/api/retirement/runs?dryRun=` (`runToggleRetirement`, `qits:system` only) is called
by qits-orchestrator's `gc` step `toggles-retirement`. A run does the following (`RetirementRun`):

1. **Reads** qits-maintenance's `GET /maintenance/api/deployed-pacts/interactions?provider=qits-toggles-service`.
   If that read fails, the run answers 502 and files nothing, so a broken read never retires a
   toggle.
2. **Records use.** Every declared slug that an evaluation interaction names gets
   `toggle_usage.last_used_at = now`. The slug comes from the interaction's concrete path
   `/toggles/api/toggle/<slug>/boolean`, or else from its provider state's `slug` param. Pacts on the
   configuration doors (landing's) keep nothing alive.
3. **Files removal tickets.** A declared toggle is unused when neither a deployed pact has used it
   nor it was declared within `qits.toggles.retire-after` (3d). Such a toggle gets one MAINTENANCE
   ticket "remove toggle `<slug>`" in project `qits.toggles.retire.project` (`qits`), through
   qits-projects' `POST /projects/api/work`. The ticket is held in `toggle_usage.removal_ticket`:
   - while it is open, no second ticket is filed;
   - one that somebody closed is not filed again;
   - one that qits-projects no longer has is filed anew.
4. **Drops tickets that are no longer needed.** When a toggle is used again while its ticket is
   open, the ticket gets a comment and is moved to DROPPED/OBSOLETE.

A dry run reads and decides the same way and writes nothing. The report lists what was used, filed,
kept (`USED`, `RECENT`, `TICKET_OPEN`, `TICKET_CLOSED`), dropped and failed.

The list and read doors show each toggle's `lastUsedAt` and `removalTicket`. Golden-master state
with both set: `a toggle has an open removal ticket, asked by an admin`, param `slug`.

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
