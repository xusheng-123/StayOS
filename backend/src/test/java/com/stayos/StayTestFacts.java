package com.stayos;

import org.springframework.jdbc.core.JdbcTemplate;

final class StayTestFacts {
    static void restore(JdbcTemplate jdbc) {
        jdbc.execute("""
            create or replace view room_operational_facts as
            select r.id room_id,
                exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,
                (exists(select 1 from room_issues i where i.room_id=r.id and i.severity='CRITICAL' and i.status<>'RESOLVED') or exists(select 1 from room_maintenance_stops m where m.room_id=r.id and m.resumed_at is null)) has_blocking_issue,
                exists(select 1 from housekeeping_tasks t where t.room_id=r.id and t.status in ('PENDING','IN_PROGRESS')) has_open_cleaning_task,
                exists(select 1 from stay_room_segments s where s.room_id=r.id) has_usage_history from rooms r
            """);
    }
}
