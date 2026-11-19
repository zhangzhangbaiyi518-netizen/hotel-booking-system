package com.hotel.hotelbookingsystem.mapper;

import com.hotel.hotelbookingsystem.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper {
    @Select("""
    SELECT id, username, password_hash AS passwordHash,
           nickname, phone, role, status,
           create_time AS createTime, update_time AS updateTime
    FROM `user`
    WHERE username = #{username}
    """)
    User selectByUsername(@Param("username") String username);

    @Insert("""
    INSERT INTO `user` (username, password_hash, role, status)
    VALUES (#{username}, #{passwordHash}, #{role}, #{status})
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(User user);
}
