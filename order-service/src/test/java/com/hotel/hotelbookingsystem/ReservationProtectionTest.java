package com.hotel.hotelbookingsystem;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.hotel.hotelbookingsystem.client.CatalogClient;
import com.hotel.hotelbookingsystem.controller.StayOrderController;
import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReservationProtectionTest {
    @Test
    void rateLimitedReservationReturns429AndDoesNotCreateAnotherOrder() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        var orders = new StayOrderService(jdbc, new TestInventoryPort(jdbc, 10));
        orders.run(null);
        var catalog = mock(CatalogClient.class);
        when(catalog.room(anyString(), anyLong())).thenReturn(ResponseEntity.ok(
                new RoomSnapshot(4L, 2L, "测试酒店", "地址", "12345", "大床房", 2, "", new BigDecimal("328"))));
        var factory = new AspectJProxyFactory(new StayOrderController(orders, catalog, new com.hotel.hotelbookingsystem.service.BookingTransactions(orders)));
        factory.addAspect(new SentinelResourceAspect());
        StayOrderController controller = factory.getProxy();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, WebDataBinderFactory binder) {
                        return Jwt.withTokenValue("test").header("alg", "HS256").subject("1").build();
                    }
                }).build();
        var rule = new FlowRule("reservation:create");
        rule.setGrade(1);
        rule.setCount(0);
        FlowRuleManager.loadRules(List.of());
        try {
            mvc.perform(post("/hotel/orders").contentType("application/json").content("{\"roomTypeId\":4}"))
                    .andExpect(status().isCreated());
            FlowRuleManager.loadRules(List.of(rule));
            mvc.perform(post("/hotel/orders").contentType("application/json").content("{\"roomTypeId\":4}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.message").value("预约请求过于频繁，请稍后重试"));
            assertEquals(1, orders.listAll(1L).size());
        } finally {
            FlowRuleManager.loadRules(List.of());
        }
    }
}
