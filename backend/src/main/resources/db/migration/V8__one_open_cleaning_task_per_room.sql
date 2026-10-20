-- Preserve the previously agreed room-level rule as well as source-level uniqueness.
-- Stage 4 only has PENDING tasks; stage 5 must extend this predicate with its open states.
CREATE UNIQUE INDEX uq_room_open_cleaning_task ON housekeeping_tasks(room_id) WHERE status='PENDING';
