-- psql variable runtime_role must name a newly created, restricted LOGIN role.
-- Run as the migration/object owner. Historical tables remain append-only to the application.
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_role";
GRANT USAGE ON SCHEMA public TO :"runtime_role";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"runtime_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"runtime_role";
GRANT INSERT ON users, user_roles, room_types, rooms, guests, stay_records, stay_guests, stay_room_segments,
    idempotency_requests, housekeeping_tasks, housekeeping_task_logs, housekeeping_assignment_batches,
    housekeeping_assignment_items, room_issues, room_issue_logs, room_maintenance_stops,
    room_maintenance_logs, room_changes, stay_extensions, guest_corrections, shift_handovers,
    handover_items, handover_events, audit_logs TO :"runtime_role";
GRANT UPDATE ON users, room_types, rooms, stay_records, stay_room_segments, idempotency_requests,
    housekeeping_tasks, room_issues, room_maintenance_stops, shift_handovers TO :"runtime_role";
GRANT UPDATE (display_name, document_type, document_ciphertext, document_mask, phone_ciphertext, phone_mask, version)
    ON guests TO :"runtime_role";
GRANT DELETE ON user_roles TO :"runtime_role";
-- No DDL, DELETE on business history, UPDATE on audit/logs, role administration or Flyway writes.
-- Reapply explicit grants after a future migration adds tables; never grant everything by default.
