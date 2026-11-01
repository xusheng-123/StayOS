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
    handover_items, handover_events, billing_policies, rate_plans, room_rate_days, service_catalog,
    service_orders, service_order_items, folios, folio_charges, payment_records, folio_events,
    business_days, cashier_shifts, cash_counts, daily_close_runs, daily_reports,
    reservations, reservation_events, inventory_days, inventory_blocks,
    channel_connections, channel_mappings, inbound_events, outbox_events, sync_attempts, reconciliation_runs,
    audit_logs TO :"runtime_role";
GRANT UPDATE ON users, room_types, rooms, stay_records, stay_room_segments, idempotency_requests,
    housekeeping_tasks, room_issues, room_maintenance_stops, shift_handovers,
    billing_policies, rate_plans, room_rate_days, service_catalog, folios, service_orders,
    business_days, cashier_shifts, daily_close_runs,
    reservations, inventory_days, inventory_blocks,
    channel_connections, channel_mappings, inbound_events, outbox_events, reconciliation_runs TO :"runtime_role";
-- sync_attempts stay append-only; inbound/outbox event rows are facts, mapping maintenance may remove a mapping.
GRANT DELETE ON user_roles, service_order_items, channel_mappings TO :"runtime_role";
-- Reservation deposits move to their folio exactly once at folio opening; the payment trigger allows only this fill.
GRANT UPDATE (folio_id) ON payment_records TO :"runtime_role";
GRANT UPDATE (display_name, document_type, document_ciphertext, document_mask, phone_ciphertext, phone_mask, version)
    ON guests TO :"runtime_role";
-- No DDL, DELETE on business history, UPDATE on audit/logs, role administration or Flyway writes.
-- Reapply explicit grants after a future migration adds tables; never grant everything by default.
