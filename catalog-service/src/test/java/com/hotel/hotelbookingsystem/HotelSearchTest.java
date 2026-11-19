package com.hotel.hotelbookingsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.hotelbookingsystem.search.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotelSearchTest {
    private JdbcTemplate jdbc;
    private HotelSearchDatabase database;
    private ElasticsearchHotelIndex index;
    private HotelSearchService service;
    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE hotel(id BIGINT PRIMARY KEY,name VARCHAR(100),address VARCHAR(255),phone VARCHAR(20))");
        jdbc.update("INSERT INTO hotel VALUES(1,'云栖海景酒店','上海黄浦区','02100001111'),"
                + "(2,'City Hotel','北京朝阳区','01055550000'),(12,'山景酒店','杭州西湖区',NULL),"
                + "(20,'100% Hotel','上海静安区','19900000000')");
        database = spy(new HotelSearchDatabase(jdbc));
        index = mock(ElasticsearchHotelIndex.class);
        service = new HotelSearchService(index, database, true);
    }
    @Test
    void healthySearchPreservesElasticsearchRankingButReturnsCurrentDatabaseDetails() throws Exception {
        jdbc.update("UPDATE hotel SET phone='01088880000' WHERE id=2");
        when(index.search(any(), eq(0), eq(10))).thenReturn(hits(2L, 1L));
        var page = service.search("", 0, 10);
        assertEquals("elasticsearch", page.source());
        assertEquals(List.of(2L, 1L), page.items().stream().map(hotel -> hotel.getId()).toList());
        assertEquals("01088880000", page.items().get(0).getPhone());
        verify(database, never()).search(any(), anyInt(), anyInt());
    }
    @Test
    void outageFallsBackToParameterizedDatabasePagination() throws Exception {
        when(index.search(any(), anyInt(), anyInt())).thenThrow(new IOException("test ES outage"));
        var first = service.search("上海", 0, 1);
        var second = service.search("上海", 1, 1);
        assertEquals("database", first.source()); assertEquals(2, first.total());
        assertEquals(1L, first.items().get(0).getId()); assertEquals(20L, second.items().get(0).getId());
        assertEquals(1, service.search("上海 云栖", 0, 10).total());
        assertEquals(1, service.search("city", 0, 10).total());
    }
    @Test
    void numericSearchUsesExactIdOrCompletePhoneAndNormalizesFullWidthInput() {
        var disabled = new HotelSearchService(index, database, false);
        assertEquals(List.of(1L), disabled.search("１", 0, 10).items().stream().map(hotel -> hotel.getId()).toList());
        assertEquals(12L, disabled.search("#００１２", 0, 10).items().get(0).getId());
        assertEquals(2L, disabled.search("01055550000", 0, 10).items().get(0).getId());
        assertEquals(0, disabled.search("#01055550000", 0, 10).total());
        assertEquals(0, disabled.search("99999999999999999999", 0, 10).total());
        verifyNoInteractions(index);
    }
    @Test
    void databaseFallbackTreatsWildcardsAndSqlSyntaxAsLiteralInput() {
        var disabled = new HotelSearchService(index, database, false);
        assertEquals(1, disabled.search("100%", 0, 10).total());
        assertEquals(0, disabled.search("_", 0, 10).total());
        assertEquals(0, disabled.search("' OR 1=1 --", 0, 10).total());
    }
    @Test
    void deletedOrRenamedSearchHitsTriggerFreshDatabaseSearch() throws Exception {
        when(index.search(any(), anyInt(), anyInt())).thenReturn(hits(1L));
        jdbc.update("UPDATE hotel SET name='新名称酒店' WHERE id=1");
        assertEquals(0, service.search("云栖", 0, 10).total());
        jdbc.update("DELETE FROM hotel WHERE id=1");
        var page = service.search("上海", 0, 10);
        assertEquals("database", page.source()); assertEquals(1, page.total());
        assertEquals(20L, page.items().get(0).getId());
    }
    @Test
    void emptyHealthyIndexResultsAreNotMisreportedAsDatabaseFallback() throws Exception {
        when(index.search(any(), anyInt(), anyInt())).thenReturn(new ElasticsearchHotelIndex.Hits(List.of(), 0, Map.of()));
        var page = service.search("不存在的酒店", 0, 10);
        assertEquals("elasticsearch", page.source()); assertEquals(0, page.total());
        verify(database, never()).search(any(), anyInt(), anyInt());
    }
    @Test
    void paginationAndKeywordLimitsAreCheckedBeforeCallingElasticsearch() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.search("", -1, 10)).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.search("", 0, 51));
        assertThrows(ResponseStatusException.class, () -> service.search("", 1000, 10));
        assertThrows(ResponseStatusException.class, () -> service.search("云".repeat(81), 0, 10));
        verifyNoInteractions(index);
    }
    @Test
    void queryDslEscapesWildcardInputAndDoesNotUseQueryStringParsing() {
        var mapper = new ObjectMapper();
        var dsl = mapper.valueToTree(ElasticsearchHotelIndex.searchDsl(HotelSearchQuery.parse("a?b*\\c")));
        assertEquals("*a\\?b\\*\\\\c*", dsl.at("/bool/must/0/bool/should/1/wildcard/name.raw/value").asText());
        assertFalse(dsl.toString().contains("query_string"));
        var id = mapper.valueToTree(ElasticsearchHotelIndex.searchDsl(HotelSearchQuery.parse("#0012")));
        assertEquals("12", id.at("/bool/should/0/term/id_text").asText());
        assertEquals(1, id.at("/bool/should").size());
        assertEquals("and", dsl.at("/bool/must/0/bool/should/0/multi_match/operator").asText());
    }

    @Test
    void ikTokenMatchesAreNotDiscardedByLiteralSubstringFiltering() throws Exception {
        jdbc.update("UPDATE hotel SET name='南京市的长江大桥酒店' WHERE id=1");
        when(index.search(any(), anyInt(), anyInt())).thenReturn(hits(1L));
        var page = service.search("南京市长江大桥", 0, 10);
        assertEquals("elasticsearch", page.source());
        assertEquals(1, page.total());
        assertEquals("南京市的长江大桥酒店", page.items().get(0).getName());
        verify(database, never()).search(any(), anyInt(), anyInt());
    }

    private ElasticsearchHotelIndex.Hits hits(Long... ids) {
        var documents = new HashMap<Long, ElasticsearchHotelIndex.IndexedHotel>();
        for (var hotel : new HotelSearchDatabase(jdbc).byIds(List.of(ids))) {
            documents.put(hotel.getId(), new ElasticsearchHotelIndex.IndexedHotel(HotelSearchQuery.normalize(hotel.getName()),
                    HotelSearchQuery.normalize(hotel.getAddress()), HotelSearchQuery.normalize(hotel.getPhone())));
        }
        return new ElasticsearchHotelIndex.Hits(List.of(ids), ids.length, documents);
    }
}
