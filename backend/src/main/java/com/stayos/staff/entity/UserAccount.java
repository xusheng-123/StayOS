package com.stayos.staff.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("users")
public class UserAccount {
    @TableId(type = IdType.AUTO) public Long id;
    public String username;
    public String passwordHash;
    public String displayName;
    public boolean enabled;
    public long securityVersion;
    public int failedLoginAttempts;
    public Instant lockedUntil;
    public Instant createdAt;
    public Instant updatedAt;
}
