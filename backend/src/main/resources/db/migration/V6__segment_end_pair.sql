-- Both fields must change together; CHECK predicates must reject NULL ambiguity.
ALTER TABLE stay_room_segments ADD CONSTRAINT ck_segment_end_pair CHECK
    ((exited_at IS NULL) = (end_reason IS NULL));
