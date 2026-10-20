-- V4 has already executed in the test database. Tighten NULL semantics incrementally.
ALTER TABLE stay_records ADD CONSTRAINT ck_stay_closed_fields CHECK
    (status <> 'CHECKED_OUT' OR (actual_check_out_at IS NOT NULL AND checked_out_by IS NOT NULL));
ALTER TABLE stay_room_segments ADD CONSTRAINT ck_segment_closed_fields CHECK
    (exited_at IS NULL OR end_reason IS NOT NULL);
ALTER TABLE guests ADD CONSTRAINT ck_guest_protected_fields CHECK
    (document_ciphertext LIKE 'v1.%' AND encryption_key_id='v1'
     AND (phone_ciphertext IS NULL OR phone_ciphertext LIKE 'v1.%'));
