-- The declared toggles, their ordered rules and the per-chain freezes (epic qits-2542).
--
-- A toggle row exists only because a declaration in this repository's code says so: the startup
-- reconcile inserts a new slug, keeps an existing one's rules, and deletes a slug no longer
-- declared (its rules and freezes go with it, by cascade). No door inserts or deletes a toggle.

--   slug               the toggle's name, ^[a-z][a-z0-9-]{1,62}$, as declared.
--   type               BOOLEAN, the only type there is.
--   description        the declaration's description, updated at every startup.
--   declared_default   the declaration's default value; the STATIC rule a new toggle starts with.
--   first_declared_at  when the reconcile first met the declaration.
--   version            moves on every rules change; a rules write names the version it read.
create table toggle (
    slug              text        primary key,
    type              text        not null check (type in ('BOOLEAN')),
    description       text        not null,
    declared_default  boolean     not null,
    first_declared_at timestamptz not null,
    version           bigint      not null
);

-- The ordered rules: the first that matches answers its own value.
--   USER    matches when the subject is in user_subjects (bearer `sub` values).
--   TIME    matches when now is at or after after_instant.
--   STATIC  always matches. Exactly one per toggle, always last.
create table toggle_rule (
    slug          text        not null references toggle (slug) on delete cascade,
    position      integer     not null check (position >= 0),
    kind          text        not null check (kind in ('USER', 'TIME', 'STATIC')),
    value         boolean     not null,
    user_subjects text[],
    after_instant timestamptz,
    primary key (slug, position),
    check (kind <> 'USER' or cardinality(user_subjects) > 0),
    check (kind <> 'TIME' or after_instant is not null)
);

-- The first evaluation for (toggle, origin trace id), answered to every later evaluation of the same
-- chain whatever the rules say by then; purged after an hour.
create table toggle_freeze (
    slug            text        not null references toggle (slug) on delete cascade,
    origin_trace_id text        not null,
    value           boolean     not null,
    frozen_at       timestamptz not null,
    primary key (slug, origin_trace_id)
);

create index toggle_freeze_frozen_at on toggle_freeze (frozen_at);
