package com.hotel.hotelbookingsystem.search;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record HotelSearchQuery(String keyword, boolean numeric, String id, String phone, List<String> terms) {
    public static HotelSearchQuery parse(String input) {
        String value = normalize(input).trim();
        if (value.codePointCount(0, value.length()) > 80) throw invalid("搜索关键词最多 80 个字符");
        boolean numeric = value.matches("#?\\d+");
        List<String> terms = value.isEmpty() || numeric ? List.of() : Arrays.asList(value.split("\\s+"));
        if (terms.size() > 10) throw invalid("请减少搜索关键词的数量");
        return new HotelSearchQuery(value, numeric,
                numeric ? value.replaceFirst("^#", "").replaceFirst("^0+(?!$)", "") : null,
                numeric && !value.startsWith("#") ? value : null, terms);
    }
    public static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    public static int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size + size > 10000) {
            throw invalid("分页范围无效，请缩小搜索条件；每页支持 1～50 条");
        }
        return page * size;
    }
    public static String sqlLike(String term) {
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public static String wildcard(String term) {
        return "*" + term.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?") + "*";
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
