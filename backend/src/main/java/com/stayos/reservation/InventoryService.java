package com.stayos.reservation;

import com.stayos.audit.AuditService;
import com.stayos.auth.BusinessActorGuard;
import com.stayos.common.ApiException;
import com.stayos.reservation.ReservationDtos.*;
import com.stayos.reservation.ReservationMapper.*;
import com.stayos.room.service.RoomCatalogService.Page;
import com.stayos.stay.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Future stop-sell ranges and the availability read model. Blocking is binary: one active range stops all sales. */
@Service
public class InventoryService {
    private static final int MAX_RANGE=60;
    private final ReservationMapper inventory;
    private final IdempotencyService requests;
    private final BusinessActorGuard actors;
    private final AuditService audit;
    private final com.stayos.integration.ChannelOutboxRecorder channelOutbox;
    public InventoryService(ReservationMapper inventory,IdempotencyService requests,BusinessActorGuard actors,AuditService audit,
        com.stayos.integration.ChannelOutboxRecorder channelOutbox) {
        this.inventory=inventory;this.requests=requests;this.actors=actors;this.audit=audit;this.channelOutbox=channelOutbox;
    }

    @Transactional(readOnly=true)
    public AvailabilityView availability(long actor,String arrivalDate,String departureDate) {
        actors.requireRead(actor,"reservation:manage");
        if(arrivalDate==null || departureDate==null) throw invalid("请提供到店与离店日期");
        LocalDate arrival,departure;
        try { arrival=LocalDate.parse(arrivalDate); departure=LocalDate.parse(departureDate); }
        catch(Exception failure) { throw invalid("日期格式不正确，应为 yyyy-MM-dd"); }
        if(!departure.isAfter(arrival)) throw invalid("离店日期必须晚于到店日期");
        if(arrival.isBefore(LocalDate.now(ReservationService.ZONE))) throw invalid("到店日期不能早于当前营业日");
        var nights=new ArrayList<LocalDate>();
        for(var d=arrival;d.isBefore(departure);d=d.plusDays(1)) { nights.add(d); if(nights.size()>MAX_RANGE) throw invalid("可售查询区间最长 "+MAX_RANGE+" 晚"); }
        var types=inventory.enabledRoomTypes();
        var result=new ArrayList<RoomTypeAvailability>();
        for(var type:types) {
            var rows=inventory.nightRows(type.id,arrival,departure.minusDays(1));
            var nightsView=rows.stream().map(n->new NightAvailability(n.stayDate.toString(),n.enabledRooms,n.occupied,n.sold,n.blocked,
                n.blocked>0?0:n.available)).toList();
            long bookable=nightsView.stream().mapToLong(NightAvailability::available).min().orElse(0);
            result.add(new RoomTypeAvailability(type.id,type.typeCode,type.typeName,nightsView,bookable));
        }
        return new AvailabilityView(arrival.toString(),departure.toString(),result);
    }

    // ---------- stop-sell blocks ----------
    @Transactional(readOnly=true)
    public Page<BlockView> blocks(long actor,boolean includeReleased,int page,int pageSize) {
        actors.requireRead(actor,"inventory:manage");
        if(page<1 || pageSize<1 || pageSize>100) throw invalid("分页参数不正确，每页最多100条");
        var items=inventory.blocks(includeReleased,pageSize,((long)page-1)*pageSize).stream().map(this::blockView).toList();
        return new Page<>(items,inventory.blockCount(includeReleased),page,pageSize);
    }
    @Transactional
    public BlockView createBlock(long actor,String key,BlockCreateInput input) {
        if(input==null || input.roomTypeId()==null || input.roomTypeId()<1) throw invalid("请提供要停售的房型");
        var start=parseDate(input.startDate(),"开始日期");
        var end=parseDate(input.endDate(),"结束日期");
        if(!end.isAfter(start)) throw invalid("结束日期必须晚于开始日期（[开始, 结束) 半开区间）");
        String reason=input.reason()==null?"":input.reason().trim();
        if(reason.isEmpty() || reason.length()>500 || reason.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
            throw invalid("请填写停售原因（1—500字）");
        var canonical=new CanonicalBlock(input.roomTypeId(),start.toString(),end.toString(),reason);
        Long previous=requests.reserveBlock(actor,"INVENTORY_BLOCK_CREATE",key,canonical);
        actors.require(actor,"inventory:manage");
        if(previous!=null) return blockView(inventory.blockById(previous));
        if(inventory.roomTypeEnabled(input.roomTypeId())!=1) throw new ApiException(404,"ROOM_TYPE_NOT_FOUND","房型不存在或已停用");
        var row=new BlockRow();
        row.roomTypeId=input.roomTypeId(); row.startDate=start; row.endDate=end; row.reason=reason; row.createdBy=actor;
        if(inventory.insertBlock(row)!=1) throw new IllegalStateException("Inventory block was not saved");
        var saved=inventory.blockById(row.id);
        channelOutbox.recordInventoryBlock(row.id,"INVENTORY_BLOCKED",Map.of(
            "blockId",row.id,"roomTypeId",row.roomTypeId,
            "typeCode",saved==null||saved.roomTypeCode==null?"":saved.roomTypeCode,
            "startDate",start.toString(),"endDate",end.toString(),"reason",reason));
        audit.success(actor,"INVENTORY_BLOCK_CREATE","INVENTORY",row.id);
        requests.completeBlock(actor,"INVENTORY_BLOCK_CREATE",key,row.id);
        return blockView(inventory.blockById(row.id));
    }
    @Transactional
    public BlockView releaseBlock(long actor,String key,long id) {
        Long previous=requests.reserveBlock(actor,"INVENTORY_BLOCK_RELEASE",key,new CanonicalBlockRelease(id));
        actors.require(actor,"inventory:manage");
        if(previous!=null) return blockView(inventory.blockById(previous));
        if(inventory.lockBlock(id)==null) throw new ApiException(404,"BLOCK_NOT_FOUND","停售区间不存在");
        var now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if(inventory.releaseBlock(id,actor,now)!=1) throw new ApiException(409,"BLOCK_STATE","该停售区间已解除");
        var released=inventory.blockById(id);
        channelOutbox.recordInventoryBlock(id,"INVENTORY_RELEASED",Map.of(
            "blockId",id,"roomTypeId",released==null?0:released.roomTypeId,
            "typeCode",released==null||released.roomTypeCode==null?"":released.roomTypeCode,
            "startDate",released==null?"":released.startDate.toString(),
            "endDate",released==null?"":released.endDate.toString(),"reason",""));
        audit.success(actor,"INVENTORY_BLOCK_RELEASE","INVENTORY",id);
        requests.completeBlock(actor,"INVENTORY_BLOCK_RELEASE",key,id);
        return blockView(inventory.blockById(id));
    }
    private BlockView blockView(BlockRow row) {
        if(row==null) throw new IllegalStateException("Inventory block receipt is incomplete");
        return new BlockView(row.id,row.roomTypeId,row.roomTypeCode,row.roomTypeName,row.startDate.toString(),row.endDate.toString(),
            row.reason,row.createdByName,row.createdAt,row.releasedByName,row.releasedAt);
    }
    private static LocalDate parseDate(String value,String label) {
        if(value==null || value.isBlank()) throw invalid("请提供"+label);
        try { return LocalDate.parse(value); } catch(Exception failure) { throw invalid(label+"格式不正确，应为 yyyy-MM-dd"); }
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    record CanonicalBlock(long roomTypeId,String startDate,String endDate,String reason) {}
    record CanonicalBlockRelease(long id) {}
}
