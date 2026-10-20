package com.stayos;

import org.springframework.jdbc.core.JdbcTemplate;

final class StayTestFacts {
    static void restore(JdbcTemplate jdbc) {
        jdbc.execute("""
            create or replace view room_operational_facts as
            select r.id room_id,
                exists(select 1 from stay_room_segments s where s.room_id=r.id and s.exited_at is null) occupied,
                false has_blocking_issue,
                exists(select 1 from housekeeping_tasks t where t.room_id=r.id and t.status='PENDING') has_open_cleaning_task,
                exists(select 1 from stay_room_segments s where s.room_id=r.id) has_usage_history from rooms r
            """);
    }
}
