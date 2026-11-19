package com.hotel.hotelbookingsystem.client;

import com.hotel.hotelbookingsystem.http.InternalFeignConfiguration;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "order-service",
        configuration = InternalFeignConfiguration.class,
        fallbackFactory = OrderSpendingClientFallbackFactory.class)
public interface OrderSpendingClient {
    @GetMapping("/internal/orders/spending")
    BigDecimal spending(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
