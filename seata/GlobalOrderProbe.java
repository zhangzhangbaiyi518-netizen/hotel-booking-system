package com.hotel.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import io.seata.tm.api.GlobalTransactionContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

/** Integration-only parent TM: hold a real AT branch between phase one and its final decision. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class GlobalOrderProbe {
    public static void main(String[] args) throws Exception {
        var application = new SpringApplicationBuilder(GlobalOrderProbe.class).web(WebApplicationType.NONE)
                .run("--spring.application.name=order-outbox-probe", "--spring.cloud.nacos.discovery.register-enabled=false");
        var transaction = GlobalTransactionContext.getCurrentOrCreate();
        boolean decided = false;
        try {
            transaction.begin(60000, "order-outbox-commit-barrier-probe");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:23330/hotel/orders"))
                    .header("Authorization", System.getenv("PROBE_AUTH"))
                    .header("Content-Type", "application/json")
                    .header(RootContext.KEY_XID, transaction.getXid())
                    .POST(HttpRequest.BodyPublishers.ofString(System.getenv("PROBE_ORDER_BODY"))).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) throw new IllegalStateException("Probe checkout failed: " + response.statusCode());
            String id = application.getBean(ObjectMapper.class).readTree(response.body()).get("id").asText();
            System.out.println("PROBE_ORDER " + id);
            System.out.flush();
            if (System.in.read() == 'c') transaction.commit();
            else transaction.rollback();
            decided = true;
            System.out.println("PROBE_DECIDED " + id);
        } finally {
            if (!decided) transaction.rollback();
            application.close();
        }
        System.exit(0);
    }
}
