package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class VipServiceTest {
    private JdbcTemplate jdbc;
    private VipService vip;
    private BigDecimal orderSpending;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"),ZoneId.of("Asia/Singapore"));
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+
                ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        orderSpending = BigDecimal.ZERO;
        vip=new VipService(jdbc,clock); vip.run(null);
    }
    @Test void allTenThresholdsAndMaxLevelProgressAreExact() {
        long[] thresholds={6,18,64,198,328,648,1888,5888,10888,100000};
        for(int i=0;i<thresholds.length;i++) {
            orderSpending = BigDecimal.valueOf(thresholds[i]).subtract(new BigDecimal("0.01"));
            assertEquals(i,vip.get(1L, orderSpending).level());
            orderSpending = BigDecimal.valueOf(thresholds[i]);
            var status=vip.get(1L, orderSpending); assertEquals(i+1,status.level());
            assertEquals(i==9?100:0,status.progress(),0.001);
        }
        assertNull(vip.get(1L, orderSpending).nextThreshold()); assertEquals(0,vip.get(2L, BigDecimal.ZERO).level());
    }
    @Test void weeklyClaimIsOncePerWeekAndNewSpendingDoesNotGrantAgain() {
        assertThrows(ResponseStatusException.class,()->vip.claim(1L, orderSpending));
        orderSpending = BigDecimal.valueOf(6);
        assertEquals(LocalDate.of(2026,11,16),vip.get(1L, orderSpending).weekStart());
        assertEquals(1,vip.claim(1L, orderSpending).tickets()); assertEquals(1,vip.claim(1L, orderSpending).tickets());
        // The pet service atomically consumes the shared coupon balance.
        jdbc.update("UPDATE vip_wallet SET tickets = tickets - 1 WHERE user_id = 1 AND tickets > 0");
        assertEquals(0,vip.get(1L, orderSpending).tickets());
        assertEquals(0,vip.get(1L, orderSpending).spending().compareTo(new BigDecimal("6")));
        orderSpending = orderSpending.add(new BigDecimal("100"));
        assertEquals(3,vip.get(1L, orderSpending).level());
        assertEquals(0,vip.claim(1L, orderSpending).tickets());
        var nextWeek=new VipService(jdbc,Clock.offset(clock,Duration.ofDays(7)));
        assertEquals(3,nextWeek.claim(1L, orderSpending).tickets()); assertEquals(0,vip.get(2L, BigDecimal.ZERO).tickets());
    }
    @Test void concurrentClaimsCreditTicketsOnlyOnce() throws Exception {
        orderSpending = BigDecimal.valueOf(100000);
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->tx.execute(status->vip.claim(1L, orderSpending)));
            var b=pool.submit(()->tx.execute(status->vip.claim(1L, orderSpending)));
            assertEquals(100,a.get(10,java.util.concurrent.TimeUnit.SECONDS).tickets());
            assertEquals(100,b.get(10,java.util.concurrent.TimeUnit.SECONDS).tickets());
            assertEquals(100,vip.get(1L, orderSpending).tickets());
        } finally { pool.shutdownNow(); }
    }
}
