package com.hotel.hotelbookingsystem.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.hotelbookingsystem.entity.Hotel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchHotelIndex {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHotelIndex.class);
    private static final String SCHEMA_VERSION = "2";
    private final RestClient client;
    private final ObjectMapper json;
    private final String alias;
    public record IndexedHotel(String name, String address, String phone) {
        public boolean matches(Hotel hotel) {
            return name.equals(HotelSearchQuery.normalize(hotel.getName()))
                    && address.equals(HotelSearchQuery.normalize(hotel.getAddress()))
                    && phone.equals(HotelSearchQuery.normalize(hotel.getPhone()));
        }
    }
    public record Hits(List<Long> ids, long total, Map<Long, IndexedHotel> indexedHotels) {}

    public ElasticsearchHotelIndex(RestClient client, ObjectMapper json, @Value("${hotel.search.alias}") String alias) {
        if (!alias.matches("[a-z0-9][a-z0-9_-]{0,119}")) throw new IllegalArgumentException("Invalid hotel search alias");
        this.client = client; this.json = json; this.alias = alias;
    }
    public String alias() { return alias; }

    public Hits search(HotelSearchQuery query, int page, int size) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("from", HotelSearchQuery.offset(page, size)); body.put("size", size);
        body.put("track_total_hits", true); body.put("_source", List.of("name", "address", "phone"));
        body.put("query", searchDsl(query));
        body.put("sort", query.keyword().isEmpty() ? List.of(Map.of("id", "asc"))
                : List.of(Map.of("_score", "desc"), Map.of("id", "asc")));
        var request = new Request("POST", "/" + alias + "/_search");
        request.addParameter("allow_partial_search_results", "false"); request.setJsonEntity(json.writeValueAsString(body));
        JsonNode response = perform(request);
        if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() != 0
                || !"eq".equals(response.path("hits").path("total").path("relation").asText())) {
            throw new IOException("Incomplete hotel search response");
        }
        var ids = new ArrayList<Long>();
        var documents = new LinkedHashMap<Long, IndexedHotel>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            Long id = Long.valueOf(hit.path("_id").asText());
            JsonNode source = hit.path("_source");
            if (!source.has("name") || !source.has("address") || !source.has("phone")) throw new IOException("Incomplete indexed hotel");
            ids.add(id);
            documents.put(id, new IndexedHotel(source.path("name").asText(), source.path("address").asText(), source.path("phone").asText()));
        }
        return new Hits(ids, response.path("hits").path("total").path("value").asLong(), documents);
    }

    public static Map<String, Object> searchDsl(HotelSearchQuery query) {
        if (query.keyword().isEmpty()) return Map.of("match_all", Map.of());
        if (query.numeric()) {
            var options = new ArrayList<Object>();
            options.add(Map.of("term", Map.of("id_text", query.id())));
            if (query.phone() != null) options.add(Map.of("term", Map.of("phone", query.phone())));
            return Map.of("bool", Map.of("should", options, "minimum_should_match", 1));
        }
        var must = new ArrayList<Object>();
        for (String term : query.terms()) {
            var options = new ArrayList<Object>();
            options.add(Map.of("multi_match", Map.of("query", term, "operator", "and", "fields", List.of("name^3", "address^2"))));
            for (String field : List.of("name.raw", "address.raw", "phone", "id_text")) {
                options.add(Map.of("wildcard", Map.of(field, Map.of("value", HotelSearchQuery.wildcard(term), "case_insensitive", true))));
            }
            must.add(Map.of("bool", Map.of("should", options, "minimum_should_match", 1)));
        }
        return Map.of("bool", Map.of("must", must));
    }

    /** Build a complete generation before atomically switching the read alias. */
    public void synchronize(List<Hotel> hotels) throws Exception {
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(hotels)));
        List<String> previous = aliasIndices();
        if (previous.size() == 1) {
            JsonNode mapping = perform(new Request("GET", "/" + previous.get(0) + "/_mapping"));
            JsonNode meta = mapping.path(previous.get(0)).path("mappings").path("_meta");
            if (SCHEMA_VERSION.equals(meta.path("hotel_schema").asText()) && fingerprint.equals(meta.path("hotel_fingerprint").asText())) return;
        }
        String staging = alias + "-g-" + UUID.randomUUID().toString().replace("-", "");
        try {
            ObjectNode definition;
            try (var input = new ClassPathResource("search/hotel-index.json").getInputStream()) { definition = (ObjectNode) json.readTree(input); }
            ((ObjectNode) definition.path("mappings")).putObject("_meta").put("hotel_schema", SCHEMA_VERSION).put("hotel_fingerprint", fingerprint);
            write("PUT", "/" + staging, definition);
            for (int from = 0; from < hotels.size(); from += 500) {
                StringBuilder bulk = new StringBuilder();
                for (Hotel hotel : hotels.subList(from, Math.min(from + 500, hotels.size()))) {
                    bulk.append(json.writeValueAsString(Map.of("index", Map.of("_index", staging, "_id", hotel.getId().toString())))).append('\n');
                    bulk.append(json.writeValueAsString(Map.of("id", hotel.getId(), "id_text", hotel.getId().toString(),
                            "name", HotelSearchQuery.normalize(hotel.getName()), "address", HotelSearchQuery.normalize(hotel.getAddress()),
                            "phone", HotelSearchQuery.normalize(hotel.getPhone())))).append('\n');
                }
                var request = new Request("POST", "/_bulk");
                request.setEntity(new NStringEntity(bulk.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
                writeTimeout(request);
                if (perform(request).path("errors").asBoolean()) throw new IOException("Hotel bulk indexing reported item failures");
            }
            var refresh = new Request("POST", "/" + staging + "/_refresh");
            writeTimeout(refresh); perform(refresh);
            if (perform(new Request("GET", "/" + staging + "/_count")).path("count").asLong() != hotels.size()) {
                throw new IOException("Hotel index document count did not match the snapshot");
            }
            var actions = new ArrayList<Object>();
            for (String index : previous) actions.add(Map.of("remove", Map.of("index", index, "alias", alias)));
            actions.add(Map.of("add", Map.of("index", staging, "alias", alias)));
            write("POST", "/_aliases", Map.of("actions", actions));
            if (!aliasIndices().equals(List.of(staging))) throw new IOException("Hotel alias switch not confirmed");
            for (String index : previous) deleteIfUnused(index);
            log.info("Hotel search index refreshed: {} hotels, alias {}", hotels.size(), alias);
        } catch (Exception exception) {
            // A timeout can occur after ES accepted the alias switch. Never delete
            // the staging index unless we can confirm that no alias uses it.
            deleteIfUnused(staging);
            throw exception;
        }
    }
    private List<String> aliasIndices() throws IOException {
        try {
            JsonNode response = perform(new Request("GET", "/_alias/" + alias));
            var indices = new ArrayList<String>(); response.fieldNames().forEachRemaining(indices::add); return indices;
        } catch (ResponseException exception) {
            if (exception.getResponse().getStatusLine().getStatusCode() == 404) return List.of();
            throw exception;
        }
    }
    private void deleteIfUnused(String index) {
        if (!index.startsWith(alias + "-g-")) return;
        try {
            JsonNode response = perform(new Request("GET", "/" + index + "/_alias"));
            if (response.path(index).path("aliases").size() == 0) perform(new Request("DELETE", "/" + index));
        } catch (Exception exception) { log.debug("Hotel index cleanup deferred for {}", index, exception); }
    }
    private JsonNode write(String method, String path, Object body) throws IOException {
        var request = new Request(method, path); request.setJsonEntity(json.writeValueAsString(body)); writeTimeout(request); return perform(request);
    }
    private void writeTimeout(Request request) {
        request.setOptions(RequestOptions.DEFAULT.toBuilder().setRequestConfig(
                RequestConfig.custom().setConnectTimeout(500).setConnectionRequestTimeout(1000).setSocketTimeout(10000).build()));
    }
    private JsonNode perform(Request request) throws IOException {
        try (var body = client.performRequest(request).getEntity().getContent()) { return json.readTree(body); }
    }
}
