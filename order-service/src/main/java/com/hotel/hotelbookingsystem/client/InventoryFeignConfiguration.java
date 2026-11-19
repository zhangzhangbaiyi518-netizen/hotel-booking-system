package com.hotel.hotelbookingsystem.client;

import feign.RequestInterceptor;
import io.seata.core.context.RootContext;
import org.springframework.context.annotation.Bean;

public class InventoryFeignConfiguration {
    @Bean
    public RequestInterceptor transactionContext() {
        return template -> {
            String xid = RootContext.getXID();
            if (xid != null) template.header(RootContext.KEY_XID, xid);
        };
    }
}
