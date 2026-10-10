-- V15 — who chose an expense's category: the system (AUTO) or the user (USER).
-- Lets "apply this rule to my history" re-categorise only expenses the user never touched.
-- Additive: the default makes the new NOT NULL column safe on the partitioned table; manually entered
-- expenses were categorised by hand, so they are backfilled to USER.
ALTER TABLE expense
    ADD COLUMN category_source TEXT NOT NULL DEFAULT 'AUTO' CHECK (category_source IN ('AUTO', 'USER'));

UPDATE expense SET category_source = 'USER' WHERE origin = 'MANUAL';
