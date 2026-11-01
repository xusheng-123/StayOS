package com.stayos.customer;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.common.ApiException;
import com.stayos.customer.CustomerDtos.*;
import com.stayos.stay.service.GuestProtection;
import com.stayos.stay.service.GuestValidation;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Limited guest profiles: master data kept separate from per-registration snapshots.
 *  Matching and merging are human decisions; identical names or shared phones never auto-link. */
@Service
public class CustomerProfileService {
    private final CustomerProfileMapper profiles;
    private final GuestProtection protection;
    private final AuditService audit;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    public CustomerProfileService(CustomerProfileMapper profiles,GuestProtection protection,AuditService audit,IdempotencyService requests,BusinessActorGuard actors) {
        this.profiles=profiles;this.protection=protection;this.audit=audit;this.requests=requests;this.actors=actors;
    }
    private static String text(String s) { return s==null?"":s.trim(); }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }

    /** Shared field rules with registration: same name/document/phone validation, no identity claim. */
    private void validate(String name,String type,String number,String phone,String notes) {
        GuestValidation.require(name,type,number,phone);
        if(notes.length()>1000) throw invalid("客历备注不能超过 1000 字");
    }
    private Map<String,Object> encrypted(String documentNumber,String phone) {
        var row=new HashMap<String,Object>();
        row.put("documentCiphertext",protection.encrypt(documentNumber,"document"));
        row.put("documentMask",protection.mask(documentNumber));
        row.put("phoneCiphertext",protection.encrypt(phone,"phone"));
        row.put("phoneMask",protection.mask(phone));
        return row;
    }

    @Transactional
    public CreatedProfile create(long actor,String requestKey,ProfileInput raw) {
        String name=text(raw.name()),type=text(raw.documentType()),number=text(raw.documentNumber()).toUpperCase(Locale.ROOT),phone=text(raw.phone()),notes=text(raw.notes());
        validate(name,type,number,phone,notes);
        var input=new ProfileInput(name,type,number,phone,notes);
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_CREATE",requestKey,input);
        actors.require(actor,"customer:manage");
        if(previous!=null) return new CreatedProfile(view(previous),profiles.similarActive(name));
        // Same-name profiles are a hint for the operator to check first, never an automatic link.
        var similar=profiles.similarActive(name);
        var row=encrypted(number,phone);row.put("name",name);row.put("documentType",type);row.put("phone",phone);row.put("notes",notes);
        if(profiles.insert(actor,row)!=1) throw new ApiException(500,"PROFILE_CREATE_FAILED","客历档案创建失败，请重试");
        long id=((Number)row.get("id")).longValue();
        requests.completeProfile(actor,"CUSTOMER_PROFILE_CREATE",requestKey,id);
        audit.success(actor,"CUSTOMER_PROFILE_CREATE","CUSTOMER_PROFILE",id);
        return new CreatedProfile(view(id),similar);
    }

    @Transactional
    public ProfileDetail update(long actor,String requestKey,long id,ProfileUpdate raw) {
        String name=text(raw.name()),type=text(raw.documentType()),number=text(raw.documentNumber()).toUpperCase(Locale.ROOT),phone=text(raw.phone()),notes=text(raw.notes());
        validate(name,type,number,phone,notes);
        var input=new ProfileInput(name,type,number,phone,notes);
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_UPDATE",requestKey,input);
        actors.require(actor,"customer:manage");
        if(previous!=null) return view(previous);
        requireProfile(id);
        var row=encrypted(number,phone);
        if(profiles.update(id,raw.version(),name,type,
                (String)row.get("documentCiphertext"),(String)row.get("documentMask"),
                (String)row.get("phoneCiphertext"),(String)row.get("phoneMask"),notes)!=1) {
            var current=profiles.row(id);
            if(current==null) throw new ApiException(404,"PROFILE_NOT_FOUND","客历档案不存在");
            if("MERGED".equals(current.status())) throw new ApiException(409,"PROFILE_MERGED","该档案已并入其他档案，不能再修改");
            throw new ApiException(409,"VERSION_CONFLICT","客历档案已被他人修改，请刷新后重试");
        }
        requests.completeProfile(actor,"CUSTOMER_PROFILE_UPDATE",requestKey,id);
        audit.success(actor,"CUSTOMER_PROFILE_UPDATE","CUSTOMER_PROFILE",id);
        return view(id);
    }

    @Transactional
    public ProfileDetail setStatus(long actor,String requestKey,long id,StatusInput raw) {
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_SET_STATUS",requestKey,raw);
        actors.require(actor,"customer:manage");
        if(previous!=null) return view(previous);
        requireProfile(id);
        if(profiles.setStatus(id,raw.status())!=1) throw new ApiException(409,"PROFILE_MERGED","该档案已并入其他档案，不能再变更状态");
        requests.completeProfile(actor,"CUSTOMER_PROFILE_SET_STATUS",requestKey,id);
        audit.success(actor,"CUSTOMER_PROFILE_SET_STATUS","CUSTOMER_PROFILE",id);
        return view(id);
    }

    @Transactional
    public ProfileDetail merge(long actor,String requestKey,long source,MergeInput raw) {
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_MERGE",requestKey,raw);
        actors.require(actor,"customer:merge");
        if(previous!=null) return view(previous);
        requireProfile(source);
        if(raw.targetId()==source) throw invalid("不能把客历档案合并到它自己");
        var target=profiles.row(raw.targetId());
        if(target==null) throw new ApiException(404,"PROFILE_NOT_FOUND","目标客历档案不存在");
        if(!"ACTIVE".equals(target.status())) throw new ApiException(409,"PROFILE_NOT_ACTIVE","目标档案不是启用状态，请先人工核验并启用后再合并");
        // One registration snapshot belongs to at most one profile (unique guest link), so the
        // move below can never collide; the database constraint is the enforced invariant.
        if(profiles.merge(source,raw.targetId())!=1) throw new ApiException(409,"PROFILE_MERGED","该档案已并入其他档案，不能重复合并");
        profiles.moveLinks(source,raw.targetId());
        requests.completeProfile(actor,"CUSTOMER_PROFILE_MERGE",requestKey,source);
        audit.success(actor,"CUSTOMER_PROFILE_MERGE","CUSTOMER_PROFILE",source);
        return view(source);
    }

    @Transactional
    public StayLinkView linkStay(long actor,String requestKey,long id,LinkInput raw) {
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_STAY_LINK",requestKey,raw);
        actors.require(actor,"customer:manage");
        if(previous!=null) {
            var link=profiles.findLink(previous,raw.stayRecordId(),raw.guestId());
            if(link==null) throw new ApiException(409,"REQUEST_IN_PROGRESS","操作正在处理中，请稍后重试原请求");
            return profiles.linkView(link.id);
        }
        var profile=profiles.row(id);
        if(profile==null) throw new ApiException(404,"PROFILE_NOT_FOUND","客历档案不存在");
        if("MERGED".equals(profile.status())) throw new ApiException(409,"PROFILE_MERGED","该档案已并入其他档案，请关联到合并后的目标档案");
        if("DISABLED".equals(profile.status())) throw new ApiException(409,"PROFILE_DISABLED","已停用的客历档案不能关联新入住");
        if(profiles.stayExists(raw.stayRecordId())!=1) throw new ApiException(404,"STAY_NOT_FOUND","入住记录不存在");
        var guest=profiles.guestOfStay(raw.stayRecordId(),raw.guestId());
        if(guest==null) throw invalid(raw.guestId()==null?"这次入住登记没有主要联系人可选":"所选住客不属于这次入住登记");
        var existing=profiles.linkByGuest(guest.guestId);
        if(existing!=null)
            throw new ApiException(409,existing.profileId==id?"PROFILE_STAY_EXISTS":"GUEST_ALREADY_LINKED",
                existing.profileId==id?"该入住登记已关联到此客历档案":"该入住登记已关联到其他客历档案，请先人工核对并解除原关联");
        var link=new HashMap<String,Object>();
        if(profiles.insertLink(actor,id,raw.stayRecordId(),guest.guestId,link)!=1) throw new ApiException(500,"PROFILE_LINK_FAILED","客历关联创建失败，请重试");
        requests.completeProfile(actor,"CUSTOMER_PROFILE_STAY_LINK",requestKey,id);
        audit.success(actor,"CUSTOMER_PROFILE_STAY_LINK","CUSTOMER_PROFILE",id);
        return profiles.linkView(((Number)link.get("id")).longValue());
    }

    @Transactional
    public ProfileDetail detachStay(long actor,String requestKey,long linkId) {
        var link=profiles.link(linkId);
        if(link==null) throw new ApiException(404,"PROFILE_STAY_LINK_NOT_FOUND","关联记录不存在或已解除");
        Long previous=requests.reserveProfile(actor,"CUSTOMER_PROFILE_STAY_UNLINK",requestKey,new DetachInput(linkId));
        actors.require(actor,"customer:manage");
        if(previous!=null) return view(previous);
        if(profiles.deleteLink(linkId)!=1) throw new ApiException(404,"PROFILE_STAY_LINK_NOT_FOUND","关联记录不存在或已解除");
        requests.completeProfile(actor,"CUSTOMER_PROFILE_STAY_UNLINK",requestKey,link.profileId);
        audit.success(actor,"CUSTOMER_PROFILE_STAY_UNLINK","CUSTOMER_PROFILE",link.profileId);
        return view(link.profileId);
    }

    @Transactional
    public Page<ProfileView> list(long actor,CustomerProfileMapper.Filter q) {
        actors.requireRead(actor,"customer:read");
        if(q.page<1) q.page=1;
        if(q.pageSize<1 || q.pageSize>100) q.pageSize=20;
        return new Page<>(profiles.list(q),profiles.count(q),q.page,q.pageSize);
    }

    /** Detail views of masked master data are audited as access facts; no decrypted value ever leaves here. */
    @Transactional
    public ProfileDetail detail(long actor,long id) {
        actors.requireRead(actor,"customer:read");
        var detail=view(id);
        audit.success(actor,"CUSTOMER_PROFILE_VIEW","CUSTOMER_PROFILE",id);
        return detail;
    }

    private void requireProfile(long id) {
        if(profiles.row(id)==null) throw new ApiException(404,"PROFILE_NOT_FOUND","客历档案不存在");
    }
    private ProfileDetail view(long id) {
        var row=profiles.row(id);
        if(row==null) throw new ApiException(404,"PROFILE_NOT_FOUND","客历档案不存在");
        var mergedInto="MERGED".equals(row.status()) && row.mergedIntoId()!=null ? profiles.mergedInto(row.mergedIntoId()) : null;
        return new ProfileDetail(row.id(),row.displayName(),row.documentType(),row.documentMask(),row.phoneMask(),
            row.notes(),row.status(),row.mergedIntoId(),row.version(),row.createdAt(),row.updatedAt(),
            mergedInto,profiles.staysOf(id));
    }
}
