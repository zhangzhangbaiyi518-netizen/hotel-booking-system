package com.hotel.hotelbookingsystem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class GatewayController {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "expect", "tx_xid");
    private static final Pattern API_PATH = Pattern.compile("/hotel(?:/[A-Za-z0-9_-]+)+/?");

    private final Map<String, String> routes;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final LoadBalancerClient loadBalancer;

    GatewayController(LoadBalancerClient loadBalancer) {
        this.loadBalancer = loadBalancer;
        routes = Map.ofEntries(
                Map.entry("auth", "account-service"), Map.entry("profile", "account-service"),
                Map.entry("searchbyphone", "catalog-service"), Map.entry("searchbyid", "catalog-service"),
                Map.entry("search", "catalog-service"),
                Map.entry("searchroombyid", "catalog-service"), Map.entry("searchall", "catalog-service"),
                Map.entry("rooms", "catalog-service"), Map.entry("orders", "order-service"),
                Map.entry("pet", "pet-service"), Map.entry("wallet", "pet-service"),
                Map.entry("vip", "engagement-service"));
    }

    @RequestMapping(path = {"/hotel/{api}", "/hotel/{api}/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    void forward(@PathVariable String api, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String serviceId = routes.get(api);
        if (serviceId == null || !API_PATH.matcher(path).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        ServiceInstance instance = loadBalancer.choose(serviceId);
        if (instance == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "后端服务暂无可用实例");
        }
        String query = request.getQueryString();
        String target = instance.getUri() + path + (query == null ? "" : "?" + query);
        byte[] body = request.getInputStream().readAllBytes();
        var builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(15))
                .method(request.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        var requestExclusions = excludedHeaders(Collections.list(request.getHeaders("Connection")));
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!requestExclusions.contains(name.toLowerCase(Locale.ROOT))) {
                Collections.list(request.getHeaders(name)).forEach(value -> builder.header(name, value));
            }
        }

        HttpResponse<byte[]> result;
        try {
            result = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "后端服务暂不可用", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请求已中断", e);
        }

        response.setStatus(result.statusCode());
        var responseExclusions = excludedHeaders(result.headers().allValues("Connection"));
        result.headers().map().forEach((name, values) -> {
            if (!responseExclusions.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        response.getOutputStream().write(result.body());
    }

    private static Set<String> excludedHeaders(List<String> connectionHeaders) {
        Set<String> exclusions = new HashSet<>(HOP_BY_HOP_HEADERS);
        for (String header : connectionHeaders) {
            for (String name : header.split(",")) {
                exclusions.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return exclusions;
    }
}
