package com.hotel.hotelbookingsystem.mapper;
import com.hotel.hotelbookingsystem.entity.Room;
import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.hotel.hotelbookingsystem.entity.Hotel;

import java.util.List;

@Mapper
public interface HotelMapper {

    @Select("SELECT * FROM hotel WHERE phone = #{phone}")
    Hotel selectByPhone(@Param("phone") String phone);


    // 查询所有酒店
    @Select("SELECT * FROM hotel")
    List<Hotel> selectAll();

    // 根据 ID 查询酒店
    @Select("SELECT * FROM hotel WHERE id = #{id}")
    Hotel selectById(@Param("id") Long id);

    // 根据房型 ID 查询房型
    @Select("SELECT id, hotel_id AS hotelId, name, price, capacity, " +
            "description, create_time AS createTime FROM room_type WHERE id = #{id}")
    Room selectRoomById(@Param("id") Long id);

    @Select("SELECT id, hotel_id AS hotelId, name, price, capacity, " +
            "description, create_time AS createTime FROM room_type " +
            "WHERE hotel_id = #{hotelId} ORDER BY id")
    List<Room> selectRoomsByHotelId(@Param("hotelId") Long hotelId);
    @Select("""
            SELECT r.id AS roomTypeId, h.id AS hotelId, h.name AS hotelName,
                   h.address AS hotelAddress, h.phone AS hotelPhone,
                   r.name AS roomName, r.capacity, r.description, r.price
            FROM room_type r JOIN hotel h ON h.id = r.hotel_id
            WHERE r.id = #{id}
            """)
    RoomSnapshot selectRoomSnapshot(@Param("id") Long id);
}
