package com.stayos.stay.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.common.ApiException;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.dto.GuestCorrectionDtos.*;
import com.stayos.stay.mapper.*;
import com.stayos.stay.mapper.GuestCorrectionMapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class GuestCorrectionService {
    private static final List<String> FIELDS=List.of("name","documentType","documentNumber","phone");
    private final GuestCorrectionMapper corrections;
    private final StayExtensionMapper stays;
    private final StayMapper requestsMapper;
    private final IdempotencyService requests;
    private final GuestProtection protection;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    private final ObjectMapper json;

    public GuestCorrectionService(GuestCorrectionMapper corrections, StayExtensionMapper stays, StayMapper requestsMapper,
                                  IdempotencyService requests, GuestProtection protection, BusinessActorGuard actors,
                                  AuditService audit, ObjectMapper json) {
        this.corrections=corrections; this.stays=stays; this.requestsMapper=requestsMapper; this.requests=requests;
        this.protection=protection; this.actors=actors; this.audit=audit; this.json=json;
    }
    private record Canonical(long stayId,long guestId,long version,Map<String,String> changes,String reason) {}

    @Transactional
    public Result correct(long actor,String key,long stayId,long guestId,Request input) {
        if (stayId<1 || guestId<1 || input==null || input.version()==null || input.version()<0
                || input.changes()==null || input.changes().isEmpty() || !FIELDS.containsAll(input.changes().keySet()))
            throw invalid("请提供当前住客版本及需要更正的字段");
        String reason=input.reason()==null?"":input.reason().trim();
        if (reason.isBlank() || reason.length()>1000 || input.reason().codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw invalid("请填写更正原因（1—1000字），不要填写证件、电话等敏感资料");
        var changes=new TreeMap<String,String>();
        for (var entry:input.changes().entrySet()) {
            JsonNode value=entry.getValue();
            if (value==null || !value.isTextual() || value.asText().codePoints().anyMatch(Character::isISOControl))
                throw invalid("更正字段须为有效文字；清空电话请明确提交空字符串");
            String text=value.asText().trim();
            if (entry.getKey().equals("documentNumber")) text=text.toUpperCase(Locale.ROOT);
            changes.put(entry.getKey(),text);
        }
        Long previous=requests.reserveCorrection(actor,key,new Canonical(stayId,guestId,input.version(),changes,reason));
        actors.require(actor,"stay:guest-correct");
        if (previous!=null) return required(previous);
        var stay=stays.lock(stayId);
        if (stay==null) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        if (!"IN_HOUSE".equals(stay.status)) throw conflict("STAY_ALREADY_CHECKED_OUT","入住已结束，不能更正住客资料");
        // Stay lock serializes checkout/change/correction; no room state is touched or locked here.
        List<Guest> guests=corrections.lockGuests(stayId);
        Guest current=guests.stream().filter(g->g.id==guestId).findFirst().orElseThrow(()->new ApiException(404,"GUEST_NOT_FOUND","该住客不属于本次入住"));
        if (corrections.registrations(guestId)!=1) throw conflict("SHARED_GUEST_SNAPSHOT","登记快照关联异常，请联系管理员核查");
        if (!current.version.equals(input.version())) throw conflict("STALE_GUEST_VERSION","住客资料已变化，请刷新后重新确认");
        var before=values(current);
        var after=new LinkedHashMap<>(before); after.putAll(changes);
        GuestValidation.require(after.get("name"),after.get("documentType"),after.get("documentNumber"),after.get("phone"));
        List<String> changed=FIELDS.stream().filter(f->!before.get(f).equals(after.get(f))).toList();
        if (changed.isEmpty()) throw invalid("资料与原登记相同，无需更正");
        for (Guest other:guests) {
            if (other.id!=guestId && other.documentType.equals(after.get("documentType"))
                    && protection.decrypt(other.documentCiphertext,"document").equals(after.get("documentNumber")))
                throw invalid("同一次入住不能重复登记相同证件");
        }
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(stay.checkInAt)) throw conflict("SERVER_TIME_INVALID","服务器时间早于入住时间，请联系管理员核查");
        var fact=new Fact(); fact.stayRecordId=stayId; fact.guestId=guestId; fact.guestVersion=current.version+1;
        fact.operatorId=actor; fact.operatedAt=now; fact.reason=reason; fact.changedFields=encode(changed);
        fact.beforeSummary=encode(summary(before)); fact.afterSummary=encode(summary(after));
        fact.beforeCiphertext=protection.encrypt(encode(before),"guest-correction");
        fact.afterCiphertext=protection.encrypt(encode(after),"guest-correction");
        String document=changed.contains("documentNumber")?protection.encrypt(after.get("documentNumber"),"document"):current.documentCiphertext;
        String phone=changed.contains("phone")?protection.encrypt(after.get("phone"),"phone"):current.phoneCiphertext;
        if (corrections.update(guestId,current.version,after.get("name"),after.get("documentType"),document,
                protection.mask(after.get("documentNumber")),phone,protection.mask(after.get("phone")))!=1 || corrections.insert(fact)!=1)
            throw new IllegalStateException("Guest correction was not saved");
        audit.success(actor,"GUEST_CORRECT","GUEST_CORRECTION",fact.id);
        requests.completeCorrection(actor,key,fact.id);
        return required(fact.id);
    }
    private Map<String,String> values(Guest guest) {
        if (!GuestProtection.KEY_ID.equals(guest.encryptionKeyId)) throw new ApiException(500,"PROTECTION_ERROR","住客资料保护版本异常，请联系管理员");
        var values=new LinkedHashMap<String,String>();
        values.put("name",guest.displayName); values.put("documentType",guest.documentType);
        values.put("documentNumber",protection.decrypt(guest.documentCiphertext,"document"));
        values.put("phone",protection.decrypt(guest.phoneCiphertext,"phone")); return values;
    }
    private Map<String,String> summary(Map<String,String> values) {
        var safe=new LinkedHashMap<String,String>();
        FIELDS.forEach(f->safe.put(f,f.equals("documentType")?values.get(f):protection.mask(values.get(f)))); return safe;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Result> history(long stay,long guest,int page,int size) {
        if (page<1 || size<1 || size>100) throw invalid("分页参数不正确，每页最多100条");
        if (!corrections.belongs(stay,guest)) throw new ApiException(404,"GUEST_NOT_FOUND","该住客不属于本次入住");
        return new Page<>(corrections.history(stay,guest,size,((long)page-1)*size).stream().map(this::result).toList(),corrections.count(stay,guest),page,size);
    }
    @Transactional(readOnly=true)
    public Result receipt(long actor,String key) {
        if (key==null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw invalid("请求标识无效");
        var request=requestsMapper.request(actor,"GUEST_CORRECT",UUID.fromString(key).toString());
        if (request==null || request.resultGuestCorrectionId==null)
            throw new ApiException(404,"OPERATION_NOT_FOUND","尚未查到成功结果，请核对住客更正历史，勿直接新建请求");
        return required(request.resultGuestCorrectionId);
    }
    private Result required(long id) {
        Fact fact=corrections.detail(id);
        if (fact==null) throw new IllegalStateException("Guest correction receipt is incomplete");
        return result(fact);
    }
    private Result result(Fact f) {
        try { return new Result(f.id,f.stayRecordId,f.guestId,f.guestVersion,f.operatorId,f.operatorName,f.operatedAt,f.reason,
                json.readValue(f.changedFields,new TypeReference<List<String>>(){}),
                json.readValue(f.beforeSummary,new TypeReference<Map<String,String>>(){}),json.readValue(f.afterSummary,new TypeReference<Map<String,String>>(){})); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid correction summary"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Correction encoding unavailable"); }
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
}
