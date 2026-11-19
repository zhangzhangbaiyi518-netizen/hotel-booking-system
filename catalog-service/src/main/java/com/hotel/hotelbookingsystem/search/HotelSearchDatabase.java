package com.hotel.hotelbookingsystem.search;

import com.hotel.hotelbookingsystem.entity.Hotel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class HotelSearchDatabase {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = "id,name,address,phone";
    private static final RowMapper<Hotel> HOTEL = (rs, row) -> {
        var hotel = new Hotel();
        hotel.setId(rs.getLong("id")); hotel.setName(rs.getString("name"));
        hotel.setAddress(rs.getString("address")); hotel.setPhone(rs.getString("phone"));
        return hotel;
    };
    public HotelSearchDatabase(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<Hotel> snapshot() { return jdbc.query("SELECT " + COLUMNS + " FROM hotel ORDER BY id", HOTEL); }

    public List<Hotel> byIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("SELECT " + COLUMNS + " FROM hotel WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", HOTEL, ids.toArray());
    }

    @Transactional(readOnly = true)
    public HotelSearchPage search(HotelSearchQuery query, int page, int size) {
        int offset = HotelSearchQuery.offset(page, size);
        List<Object> parameters = new ArrayList<>();
        String where = "";
        if (query.numeric()) {
            where = " WHERE (id=? OR phone=?)";
            Long id;
            try { id = Long.valueOf(query.id()); } catch (NumberFormatException exception) { id = null; }
            parameters.add(id); parameters.add(query.phone());
        } else if (!query.terms().isEmpty()) {
            var clauses = new ArrayList<String>();
            for (String term : query.terms()) {
                clauses.add("(LOWER(name) LIKE ? ESCAPE '!' OR LOWER(address) LIKE ? ESCAPE '!' "
                        + "OR LOWER(phone) LIKE ? ESCAPE '!' OR CAST(id AS CHAR(20)) LIKE ? ESCAPE '!')");
                for (int i = 0; i < 4; i++) parameters.add(HotelSearchQuery.sqlLike(term));
            }
            where = " WHERE " + String.join(" AND ", clauses);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM hotel" + where, Long.class, parameters.toArray());
        parameters.add(size); parameters.add(offset);
        var hotels = jdbc.query("SELECT " + COLUMNS + " FROM hotel" + where + " ORDER BY id LIMIT ? OFFSET ?", HOTEL, parameters.toArray());
        return new HotelSearchPage(hotels, total == null ? 0 : total, page, size, "database");
    }
}
