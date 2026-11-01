package com.stayos.customer;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class CustomerDtos {
    private CustomerDtos() {}
    public record ProfileInput(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="ID_CARD|PASSPORT|OTHER") String documentType,
        @NotBlank @Size(max=64) String documentNumber,
        @Size(max=30) String phone,@Size(max=1000) String notes) {}
    public record ProfileUpdate(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="ID_CARD|PASSPORT|OTHER") String documentType,
        @NotBlank @Size(max=64) String documentNumber,
        @Size(max=30) String phone,@Size(max=1000) String notes,@NotNull @Positive long version) {}
    public record StatusInput(@NotBlank @Pattern(regexp="ACTIVE|DISABLED") String status) {}
    public record MergeInput(@NotNull @Positive long targetId) {}
    public record LinkInput(@NotNull @Positive long stayRecordId, Long guestId) {}
    public record DetachInput(@NotNull @Positive long linkId) {}
    public record ProfileView(long id,String displayName,String documentType,String documentMask,String phoneMask,
        String notes,String status,Long mergedIntoId,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
    public record MergedTarget(long id,String displayName,String documentMask) {}
    public record StayLinkView(long linkId,long stayRecordId,long guestId,String guestName,boolean primary,
        String stayStatus,OffsetDateTime checkInAt,OffsetDateTime expectedCheckOutAt,String roomNumber) {}
    public record ProfileDetail(long id,String displayName,String documentType,String documentMask,String phoneMask,
        String notes,String status,Long mergedIntoId,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,
        MergedTarget mergedInto,List<StayLinkView> stays) {}
    public record SimilarProfile(long id,String displayName,String documentMask,String phoneMask,String status) {}
    public record CreatedProfile(ProfileDetail profile,List<SimilarProfile> similarActiveProfiles) {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
}
