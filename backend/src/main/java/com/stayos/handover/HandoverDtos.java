package com.stayos.handover;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class HandoverDtos {
    private HandoverDtos() {}
    public record ItemInput(String category, String content, Long sourceId) {}
    public record CreateRequest(String scope, Long receiverId, OffsetDateTime shiftStartAt, OffsetDateTime shiftEndAt,
                                String summary, List<ItemInput> items) {}
    public record UpdateRequest(Long version, Long receiverId, OffsetDateTime shiftStartAt, OffsetDateTime shiftEndAt,
                                String summary, List<ItemInput> items) {}
    public record SubmitRequest(Long version) {}
    public record ReassignRequest(Long version, Long receiverId, String reason) {}
    public record Item(long id, int position, String category, String content, Long sourceId) {}
    public record Event(long id, String eventType, long actorId, String actorName, String reason,
                        Map<String,Object> detail, OffsetDateTime createdAt) {}
    public record Summary(long id, String scope, String status, long version,
                          long handoverById, String handoverByName, long receiverId, String receiverName,
                          OffsetDateTime shiftStartAt, OffsetDateTime shiftEndAt, String summary, long itemCount,
                          OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime acknowledgedAt) {}
    public record Detail(long id, String scope, String status, long version,
                         long handoverById, String handoverByName, long receiverId, String receiverName,
                         OffsetDateTime shiftStartAt, OffsetDateTime shiftEndAt, String summary,
                         OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime acknowledgedAt,
                         List<Item> items, List<Event> events) {}
    public record Candidate(long id, String displayName, String username, List<String> roleNames) {}
}
