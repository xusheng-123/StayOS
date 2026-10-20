package com.stayos.room.dto;

import com.stayos.room.entity.Room;

public class RoomView extends Room {
    public String roomTypeName;
    public String roomTypeCode;
    public Boolean roomTypeEnabled;
    public Boolean occupied;
    public Boolean hasOpenIssue,hasCriticalIssue,hasManualMaintenanceStop;
    public Boolean hasBlockingIssue;
    public Boolean hasOpenCleaningTask;
    public Boolean hasUsageHistory;
    public String occupancyStatus;
    public String maintenanceStatus;
    public String enabledStatus;
    public Boolean canCheckIn;
    public String availability;
}
