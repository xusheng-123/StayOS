package com.stayos.handover;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.auth.RolePermissions;
import com.stayos.common.ApiException;
import com.stayos.handover.HandoverDtos.*;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.staff.mapper.UserMapper;
import com.stayos.stay.mapper.StayMapper;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Non-financial shift handover. Lock order: idempotency -> actor guard -> handover row. No stay/room locks. */
@Service
public class HandoverService {
    static final Set<String> SCOPES=Set.of("FRONT_DESK","HOUSEKEEPING");
    static final Set<String> STATUSES=Set.of("DRAFT","SUBMITTED","ACKNOWLEDGED");
    private static final Set<String> CATEGORIES=Set.of("OVERDUE_STAY","STRANDED_TASK","DELAYED_TASK","CRITICAL_ISSUE","MAINTENANCE_STOP","CUSTOM");
    private static final Set<String> OPERATIONS=Set.of("HANDOVER_CREATE","HANDOVER_SUBMIT","HANDOVER_ACKNOWLEDGE");
    private static final long MAX_ITEMS=50,MAX_SPAN_DAYS=31,TIME_BOUND_DAYS=366;
    private final HandoverMapper handovers;
    private final StayMapper requestsMapper;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final UserMapper users;
    private final AuditService audit;
    private final ObjectMapper json;

    public HandoverService(HandoverMapper handovers,StayMapper requestsMapper,IdempotencyService requests,
                           BusinessActorGuard actors,UserMapper users,AuditService audit,ObjectMapper json) {
        this.handovers=handovers; this.requestsMapper=requestsMapper; this.requests=requests; this.actors=actors;
        this.users=users; this.audit=audit; this.json=json;
    }
    static String permission(String scope) {
        if ("FRONT_DESK".equals(scope)) return "handover:front-desk";
        if ("HOUSEKEEPING".equals(scope)) return "handover:housekeeping";
        throw new ApiException(400,"VALIDATION_ERROR","交班范围只能是前台或客房");
    }
    private record CanonicalItem(String category,String content,Long sourceId) {}
    private record CanonicalCreate(String scope,long receiverId,Instant shiftStartAt,Instant shiftEndAt,
                                   String summary,List<CanonicalItem> items) {}
    private record CanonicalAction(long handoverId,long version) {}

    @Transactional
    public Detail create(long actor,String key,CreateRequest input) {
        if (input==null || input.scope()==null || input.receiverId()==null || input.receiverId()<1
                || input.shiftStartAt()==null || input.shiftEndAt()==null) throw invalid("请提供交班范围、接班人与班次起止时间");
        String scope=input.scope();
        validateTimes(input.shiftStartAt(),input.shiftEndAt());
        var content=validateContent(scope,input.summary(),input.items());
        var canonical=new CanonicalCreate(scope,input.receiverId(),input.shiftStartAt().toInstant(),input.shiftEndAt().toInstant(),
            content.summary(),content.items().stream().map(i->new CanonicalItem(i.category(),i.content(),i.sourceId())).toList());
        Long previous=requests.reserveHandover(actor,"HANDOVER_CREATE",key,canonical);
        actors.require(actor,permission(scope));
        if (previous!=null) return required(previous);
        long receiverId=input.receiverId();
        requireReceiver(receiverId,scope,actor);
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var row=new HandoverMapper.Row();
        row.scope=scope; row.handoverBy=actor; row.receiverId=receiverId;
        row.shiftStartAt=input.shiftStartAt().truncatedTo(ChronoUnit.MICROS); row.shiftEndAt=input.shiftEndAt().truncatedTo(ChronoUnit.MICROS);
        row.summary=content.summary();
        if (handovers.insert(row)!=1) throw new IllegalStateException("Handover was not saved");
        saveItems(content.items(),row.id);
        recordEvent(row.id,"CREATED",actor,null,null);
        audit.success(actor,"HANDOVER_CREATE","HANDOVER",row.id);
        requests.completeHandover(actor,"HANDOVER_CREATE",key,row.id);
        return required(row.id);
    }

    @Transactional
    public Detail update(long actor,long id,UpdateRequest input) {
        if (input==null || input.version()==null || input.version()<0) throw invalid("请提供当前交班版本");
        var row=handovers.lock(id);
        if (row==null) throw notFound();
        actors.require(actor,permission(row.scope));
        if (row.handoverBy!=actor) throw new ApiException(403,"ACCESS_DENIED","只有交班人可以修改自己的交班草稿");
        if (row.version!=input.version()) throw conflict("STALE_VERSION","交班草稿已被其他人修改，请刷新后重新核对");
        if (!"DRAFT".equals(row.status)) throw conflict("HANDOVER_STATE","交班已提交，摘要与事项已冻结，不能修改");
        var content=validateContent(row.scope,input.summary(),input.items());
        if (input.receiverId()==null || input.receiverId()<1) throw invalid("请选择接班人");
        requireReceiver(input.receiverId(),row.scope,actor);
        validateTimes(input.shiftStartAt(),input.shiftEndAt());
        if (handovers.updateDraft(id,input.version(),input.receiverId(),
                input.shiftStartAt().truncatedTo(ChronoUnit.MICROS),input.shiftEndAt().truncatedTo(ChronoUnit.MICROS),content.summary())!=1)
            throw conflict("STALE_VERSION","交班草稿已被其他人修改，请刷新后重新核对");
        handovers.deleteItems(id);
        saveItems(content.items(),id);
        recordEvent(id,"UPDATED",actor,null,null);
        audit.success(actor,"HANDOVER_UPDATE","HANDOVER",id);
        return required(id);
    }

    @Transactional
    public Detail submit(long actor,String key,long id,SubmitRequest input) {
        if (input==null || input.version()==null || input.version()<0) throw invalid("请提供当前交班版本");
        var current=handovers.byId(id);
        if (current==null) throw notFound();
        Long previous=requests.reserveHandover(actor,"HANDOVER_SUBMIT",key,new CanonicalAction(id,input.version()));
        actors.require(actor,permission(current.scope));
        if (previous!=null) return required(previous);
        var row=handovers.lock(id);
        if (row==null) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        if (row.handoverBy!=actor) throw new ApiException(403,"ACCESS_DENIED","只有交班人可以提交自己的交班");
        if (row.version!=input.version()) throw conflict("STALE_VERSION","交班草稿已变化，请刷新后重新核对");
        if (!"DRAFT".equals(row.status)) throw conflict("HANDOVER_STATE","交班已提交或已确认，不能重复提交");
        // Submission re-validates the receiver's current account state and scope permission.
        requireReceiver(row.receiverId,row.scope,actor);
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (handovers.submit(id,input.version(),now)!=1) throw conflict("STALE_VERSION","交班草稿已变化，请刷新后重新核对");
        recordEvent(id,"SUBMITTED",actor,null,null);
        audit.success(actor,"HANDOVER_SUBMIT","HANDOVER",id);
        requests.completeHandover(actor,"HANDOVER_SUBMIT",key,id);
        return required(id);
    }

    @Transactional
    public Detail reassign(long actor,long id,ReassignRequest input) {
        if (input==null || input.version()==null || input.version()<0 || input.receiverId()==null || input.receiverId()<1)
            throw invalid("请提供当前交班版本与新的接班人");
        String reason=validateReason(input.reason());
        var current=handovers.byId(id);
        if (current==null) throw notFound();
        actors.require(actor,permission(current.scope));
        var row=handovers.lock(id);
        if (row==null) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        if (row.handoverBy!=actor) throw new ApiException(403,"ACCESS_DENIED","只有交班人可以重新指定接班人");
        if (row.version!=input.version()) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        if (!"SUBMITTED".equals(row.status)) throw conflict("HANDOVER_STATE","只能在已提交、待确认的交班上重新指定接班人");
        if (input.receiverId()==row.receiverId.longValue()) throw invalid("新接班人与当前接班人相同");
        requireReceiver(input.receiverId(),row.scope,actor);
        String fromName=handovers.byId(id).receiverName;
        if (handovers.reassign(id,input.version(),input.receiverId())!=1) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        recordEvent(id,"REASSIGNED",actor,reason,encode(Map.of("fromReceiverId",row.receiverId,"fromReceiverName",fromName,
            "toReceiverId",input.receiverId(),"toReceiverName",handovers.byId(id).receiverName)));
        audit.success(actor,"HANDOVER_REASSIGN","HANDOVER",id);
        return required(id);
    }

    @Transactional
    public Detail acknowledge(long actor,String key,long id,SubmitRequest input) {
        if (input==null || input.version()==null || input.version()<0) throw invalid("请提供当前交班版本");
        var current=handovers.byId(id);
        if (current==null) throw notFound();
        Long previous=requests.reserveHandover(actor,"HANDOVER_ACKNOWLEDGE",key,new CanonicalAction(id,input.version()));
        actors.require(actor,permission(current.scope));
        if (previous!=null) return required(previous);
        var row=handovers.lock(id);
        if (row==null) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        // A lost race (e.g. concurrent reassign) is a stale version first; identity is judged
        // against the version the actor actually saw, so refresh advice is not misleading.
        if (row.version!=input.version()) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        if (row.receiverId!=actor) throw new ApiException(403,"ACCESS_DENIED","只有接班人本人可以确认接班");
        if (!"SUBMITTED".equals(row.status)) throw conflict("HANDOVER_STATE","交班不在待确认状态，不能确认接班");
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (handovers.acknowledge(id,input.version(),actor,now)!=1) throw conflict("STALE_VERSION","交班状态已变化，请刷新后重新核对");
        recordEvent(id,"ACKNOWLEDGED",actor,null,null);
        audit.success(actor,"HANDOVER_ACKNOWLEDGE","HANDOVER",id);
        requests.completeHandover(actor,"HANDOVER_ACKNOWLEDGE",key,id);
        return required(id);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Summary> list(long actor,String scope,String status,boolean receiving,boolean mine,int page,int size) {
        if (!SCOPES.contains(scope)) throw invalid("交班范围只能是前台或客房");
        if (status!=null && !STATUSES.contains(status)) throw invalid("交班状态不正确");
        if (page<1 || size<1 || size>100) throw invalid("分页参数不正确，每页最多100条");
        requireRead(actor,permission(scope));
        return new Page<>(handovers.list(actor,scope,status,receiving,mine,size,((long)page-1)*size).stream().map(this::summary).toList(),
            handovers.count(actor,scope,status,receiving,mine),page,size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(long actor,long id) {
        var row=handovers.byId(id);
        if (row==null) throw notFound();
        requireRead(actor,permission(row.scope));
        return required(id);
    }

    @Transactional(readOnly=true)
    public Detail receipt(long actor,String operation,String key) {
        if (key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") || !OPERATIONS.contains(operation))
            throw invalid("请求标识或操作类型无效");
        var request=requestsMapper.request(actor,operation,UUID.fromString(key).toString());
        if (request==null || request.resultHandoverId==null)
            throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对交班记录，勿直接新建请求");
        return required(request.resultHandoverId);
    }

    @Transactional(readOnly=true)
    public List<Candidate> candidates(long actor,String scope) {
        requireRead(actor,permission(scope));
        var result=new ArrayList<Candidate>();
        for (var row:handovers.candidates()) {
            var roles=Arrays.stream(row.roleCodes.split(",")).filter(code->!code.isBlank()).toList();
            if (RolePermissions.permissions(roles).contains(permission(scope)))
                result.add(new Candidate(row.id,row.displayName,row.username,roles));
        }
        return result;
    }

    private void requireRead(long actor,String permission) {
        var user=users.selectById(actor);
        if (user==null || !user.enabled) throw new ApiException(403,"ACCOUNT_UNAVAILABLE","账号状态已变化，请重新登录");
        if (!RolePermissions.permissions(users.roles(actor)).contains(permission)) throw new ApiException(403,"ACCESS_DENIED","当前账号没有该业务范围的交班权限");
    }
    private void requireReceiver(long receiverId,String scope,long actor) {
        if (receiverId==actor) throw invalid("不能把班交给自己");
        var user=users.selectById(receiverId);
        if (user==null || !user.enabled) throw conflict("HANDOVER_RECEIVER","接班人账号不存在或已停用，请重新选择");
        if (!RolePermissions.permissions(users.roles(receiverId)).contains(permission(scope)))
            throw conflict("HANDOVER_RECEIVER","接班人没有该业务范围的交班权限，请重新选择");
    }
    private record Content(String summary,List<ItemInput> items) {}
    private Content validateContent(String scope,String summary,List<ItemInput> items) {
        permission(scope);
        String clean=summary==null?"":summary.trim();
        if (clean.length()>2000 || hasControl(clean)) throw invalid("交班摘要最长2000字，且不能包含特殊控制字符");
        if (items==null) items=List.of();
        if (items.size()>MAX_ITEMS) throw invalid("交班事项最多50条");
        var normalized=new ArrayList<ItemInput>();
        for (ItemInput item:items) {
            if (item==null || item.category()==null || !CATEGORIES.contains(item.category())) throw invalid("事项类别不正确");
            String text=item.content()==null?"":item.content().trim();
            if (text.isEmpty() || text.length()>500 || hasControl(text)) throw invalid("每条事项需填写1—500字，且不能包含特殊控制字符");
            if (item.sourceId()!=null && item.sourceId()<1) throw invalid("事项来源编号不正确");
            normalized.add(new ItemInput(item.category(),text,item.sourceId()));
        }
        return new Content(clean,List.copyOf(normalized));
    }
    private void saveItems(List<ItemInput> items,long handoverId) {
        for (int position=0;position<items.size();position++) {
            var item=new HandoverMapper.ItemRow();
            item.handoverId=handoverId; item.position=position; item.category=items.get(position).category();
            item.content=items.get(position).content(); item.sourceId=items.get(position).sourceId();
            if (handovers.insertItem(item)!=1) throw new IllegalStateException("Handover item was not saved");
        }
    }
    private void validateTimes(OffsetDateTime start,OffsetDateTime end) {
        if (start==null || end==null) throw invalid("请提供班次起止时间");
        if (!end.isAfter(start)) throw invalid("班次结束时间必须晚于开始时间");
        if (Duration.between(start,end).compareTo(Duration.ofDays(MAX_SPAN_DAYS))>0) throw invalid("班次时长不能超过31天，请核对起止时间");
        Instant now=Instant.now(),bound=now.minus(Duration.ofDays(TIME_BOUND_DAYS)),ceiling=now.plus(Duration.ofDays(TIME_BOUND_DAYS));
        if (start.toInstant().isBefore(bound) || end.toInstant().isAfter(ceiling))
            throw invalid("班次起止时间需在实际时间前后一年内");
    }
    private String validateReason(String reason) {
        String clean=reason==null?"":reason.trim();
        if (clean.isEmpty() || clean.length()>1000 || hasControl(clean)) throw invalid("请填写重新指定接班人的原因（1—1000字）");
        return clean;
    }
    private void recordEvent(long id,String type,long actor,String reason,String detail) {
        var event=new HandoverMapper.EventRow();
        event.handoverId=id; event.eventType=type; event.actorId=actor; event.reason=reason;
        event.detail=detail==null?"{}":detail;
        if (handovers.insertEvent(event)!=1) throw new IllegalStateException("Handover event was not saved");
    }
    private Summary summary(HandoverMapper.Row row) {
        return new Summary(row.id,row.scope,row.status,row.version,row.handoverBy,row.handoverByName,row.receiverId,row.receiverName,
            row.shiftStartAt,row.shiftEndAt,row.summary,row.itemCount==null?0:row.itemCount,row.createdAt,row.submittedAt,row.acknowledgedAt);
    }
    private Detail required(long id) {
        var row=handovers.byId(id);
        if (row==null) throw new IllegalStateException("Handover receipt is incomplete");
        try {
            var items=handovers.items(id).stream().map(i->new Item(i.id,i.position,i.category,i.content,i.sourceId)).toList();
            var events=new ArrayList<Event>();
            for (var e:handovers.events(id)) events.add(new Event(e.id,e.eventType,e.actorId,e.actorName,e.reason,
                json.readValue(e.detail,new TypeReference<Map<String,Object>>(){}),e.createdAt));
            return new Detail(row.id,row.scope,row.status,row.version,row.handoverBy,row.handoverByName,row.receiverId,row.receiverName,
                row.shiftStartAt,row.shiftEndAt,row.summary,row.createdAt,row.submittedAt,row.acknowledgedAt,items,events);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid handover event detail"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Handover encoding unavailable"); }
    }
    private boolean hasControl(String value) {
        return value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t');
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private static ApiException notFound() { return new ApiException(404,"HANDOVER_NOT_FOUND","交班记录不存在"); }
}
