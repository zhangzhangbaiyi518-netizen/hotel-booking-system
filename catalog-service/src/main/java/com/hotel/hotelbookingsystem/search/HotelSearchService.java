package com.hotel.hotelbookingsystem.search;

import com.hotel.hotelbookingsystem.entity.Hotel;
import java.util.HashMap;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HotelSearchService {
    private static final Logger log = LoggerFactory.getLogger(HotelSearchService.class);
    private final ElasticsearchHotelIndex index;
    private final HotelSearchDatabase database;
    private final boolean enabled;
    public HotelSearchService(ElasticsearchHotelIndex index, HotelSearchDatabase database, @Value("${hotel.search.enabled}") boolean enabled) {
        this.index = index; this.database = database; this.enabled = enabled;
    }
    public HotelSearchPage search(String keyword, int page, int size) {
        HotelSearchQuery query = HotelSearchQuery.parse(keyword);
        HotelSearchQuery.offset(page, size);
        if (enabled) {
            try {
                var result = index.search(query, page, size);
                var current = new HashMap<Long, Hotel>();
                for (Hotel hotel : database.byIds(result.ids())) current.put(hotel.getId(), hotel);
                var hotels = new ArrayList<Hotel>();
                for (Long id : result.ids()) {
                    Hotel hotel = current.get(id);
                    var indexed = result.indexedHotels().get(id);
                    // Compare indexed fields with live SQL data. Literal substring
                    // checks would discard valid matches produced by IK tokenization.
                    if (hotel == null || indexed == null || !indexed.matches(hotel)) return database.search(query, page, size);
                    hotels.add(hotel);
                }
                return new HotelSearchPage(hotels, result.total(), page, size, "elasticsearch");
            } catch (Exception exception) { log.debug("Hotel search falling back to database", exception); }
        }
        return database.search(query, page, size);
    }
}
