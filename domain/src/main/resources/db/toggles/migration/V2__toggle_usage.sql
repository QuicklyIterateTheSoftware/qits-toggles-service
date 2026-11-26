-- A toggle's use by deployed consumers' pacts, and its open removal ticket (qits-2714).
--
-- The retirement run (POST /toggles/api/retirement/runs) reads qits-maintenance's deployed pact
-- interactions for qits-toggles-service; every declared slug it sees there gets last_used_at = now.
-- A slug unused (last_used_at, else toggle.first_declared_at) for qits.toggles.retire-after gets one
-- MAINTENANCE ticket "remove toggle <slug>" in qits-projects, held here until it is closed or moot.
-- The row goes with its toggle (cascade): an undeclared toggle needs no usage.
--
--   slug            the toggle.
--   last_used_at    the last run that saw a deployed pact evaluate it; null while none ever has.
--   removal_ticket  the open "remove toggle" ticket, by qualified id (qits-123) or entity id; null
--                   while none is open.
create table toggle_usage (
    slug           text        primary key references toggle (slug) on delete cascade,
    last_used_at   timestamptz,
    removal_ticket text
);
