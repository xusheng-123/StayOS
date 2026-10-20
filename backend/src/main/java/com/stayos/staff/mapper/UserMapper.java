package com.stayos.staff.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stayos.staff.entity.UserAccount;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserAccount> {
    @Select("select * from users where username=#{username} for update")
    UserAccount lockByUsername(String username);
    @Select("select * from users where id=#{id} for update")
    UserAccount lockById(long id);
    @Select("select r.role_code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=#{id} order by r.role_code")
    List<String> roles(long id);
    @Update("update users set failed_login_attempts=#{attempts}, locked_until=#{until} where id=#{id}")
    void loginAttempts(@Param("id") long id, @Param("attempts") int attempts, @Param("until") Instant until);
    @Insert("insert into user_roles(user_id,role_id) select #{id},id from roles where role_code=#{role}")
    int addRole(@Param("id") long id, @Param("role") String role);
    @Delete("delete from user_roles where user_id=#{id}") void removeRoles(long id);
    @Update("update users set enabled=#{enabled} where id=#{id}")
    void setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);
    @Update("update users set password_hash=#{hash},failed_login_attempts=0,locked_until=null where id=#{id}")
    void setPassword(@Param("id") long id, @Param("hash") String hash);
    @Select("select 1 from pg_advisory_xact_lock(47910201)") int lockAccountAdministration();
    @Select("select count(*) from user_roles ur join roles r on r.id=ur.role_id where r.role_code='ADMIN'")
    int administratorCount();
    @Select("select count(*) from users u join user_roles ur on u.id=ur.user_id join roles r on r.id=ur.role_id where u.enabled and r.role_code='ADMIN'")
    int enabledAdministratorCount();
}
