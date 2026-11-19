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

class CultivationServiceTest {
    private JdbcTemplate jdbc;
    private CultivationService training;
    private SpiritPetService pets;
    private Clock clock=Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"),ZoneId.of("Asia/Singapore"));
    private static String id() { return UUID.randomUUID().toString(); }
    private BigDecimal balance(long user) { return training.wallet(user).balance(); }
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+id()+
                ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        pets=new SpiritPetService(jdbc); pets.run(null);
        training=new CultivationService(jdbc,clock); pets.get(1L);
    }
    private void hatch() { jdbc.update("UPDATE spirit_pet SET hatch_value=100,rarity='C' WHERE user_id=1"); }
    @Test void rechargeIsValidatedIdempotentAndDoesNotIncreaseVip() {
        String request=id(); training.recharge(1L,request,new BigDecimal("500"));
        training.recharge(1L,request,new BigDecimal("500.00"));
        assertEquals(0,balance(1L).compareTo(new BigDecimal("500")));
        assertEquals(0,balance(2L).signum());
        assertEquals(0,new PetSpendingService(jdbc).spending(1L).intValueExact());
        assertThrows(ResponseStatusException.class,()->training.recharge(1L,request,new BigDecimal("600")));
        for(String amount:new String[]{"-1","0","100001","1.001"})
            assertThrows(ResponseStatusException.class,()->training.recharge(1L,id(),new BigDecimal(amount)));
    }
    @Test void cultivationRequiresHatchingAndShopPurchasesUseServerPriceOnlyOnce() {
        assertFalse(training.get(1L).enabled()); assertTrue(training.get(1L).shop().isEmpty());
        assertThrows(ResponseStatusException.class,()->training.start(1L,1));
        hatch(); assertThrows(ResponseStatusException.class,()->training.buy(1L,id(),1,"nectar"));
        assertEquals("陌生",training.start(1L,1).stage());
        assertEquals(6,training.get(1L).shop().size());
        assertThrows(ResponseStatusException.class,()->training.buy(1L,id(),1,"nectar"));
        assertEquals(0,training.get(1L).value());
        training.recharge(1L,id(),new BigDecimal("1000"));
        String buy=id(); var result=training.buy(1L,buy,1,"pill");
        assertEquals(500,result.value()); assertEquals("陌生",result.stage());
        assertEquals(0,result.balance().compareTo(new BigDecimal("700")));
        assertEquals(result,training.buy(1L,buy,1,"pill"));
        assertEquals(300,new PetSpendingService(jdbc).spending(1L).intValueExact()); // 300 of supplement spending.
        assertThrows(ResponseStatusException.class,()->training.buy(1L,buy,1,"fruit"));
        assertThrows(ResponseStatusException.class,()->training.buy(1L,id(),99,"fruit"));
        assertThrows(ResponseStatusException.class,()->training.buy(1L,id(),1,"fake"));
        assertEquals(0,balance(2L).signum());
    }
    @Test void maxProgressDailyRewardAndRestartDoNotAllowDoubleRewards() {
        hatch(); training.start(1L,1); training.recharge(1L,id(),new BigDecimal("10000"));
        assertThrows(ResponseStatusException.class,()->training.reward(1L,1));
        training.buy(1L,id(),1,"elixir"); assertEquals("相识",training.get(1L).stage());
        training.buy(1L,id(),1,"elixir"); assertEquals("熟悉",training.get(1L).stage());
        training.buy(1L,id(),1,"elixir"); assertEquals("友好",training.get(1L).stage());
        training.buy(1L,id(),1,"elixir");
        training.buy(1L,id(),1,"elixir"); assertEquals(10000,training.get(1L).value());
        assertEquals("形影不离",training.get(1L).stage());
        assertThrows(ResponseStatusException.class,()->training.buy(1L,id(),1,"nectar"));
        BigDecimal before=balance(1L);
        var reward=training.reward(1L,1); assertTrue(reward.amount()>=100 && reward.amount()<=1000);
        assertEquals(0,balance(1L).compareTo(before.add(BigDecimal.valueOf(reward.amount()))));
        assertEquals(reward.amount(),training.reward(1L,1).amount());
        BigDecimal saved=balance(1L); pets.restart(1L,id(),1);
        assertEquals(0,training.get(1L).value()); assertFalse(training.get(1L).enabled());
        assertEquals(saved,balance(1L));
        jdbc.update("UPDATE spirit_pet SET hatch_value=100,training_enabled=1,training_value=10000 WHERE user_id=1");
        assertEquals(reward.amount(),training.reward(1L,2).amount()); assertEquals(saved,balance(1L));
        var tomorrow=new CultivationService(jdbc,Clock.offset(clock,Duration.ofDays(1)));
        assertFalse(tomorrow.get(1L).rewardClaimed());
        assertTrue(tomorrow.reward(1L,2).amount()>=100);
    }
    @Test void concurrentRechargeRetriesCreditOnePayment() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); String request=id();
        try {
            var a=pool.submit(()->tx.execute(s->training.recharge(1L,request,new BigDecimal("1000"))));
            var b=pool.submit(()->tx.execute(s->training.recharge(1L,request,new BigDecimal("1000"))));
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0,balance(1L).compareTo(new BigDecimal("1000")));
            assertEquals(1,training.wallet(1L).transactions().size());
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentPurchasesAndRewardsDoNotDoubleSpendOrCredit() throws Exception {
        hatch(); training.start(1L,1); training.recharge(1L,id(),new BigDecimal("1000"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); String purchase=id();
        try {
            var a=pool.submit(()->tx.execute(s->training.buy(1L,purchase,1,"nectar")));
            var b=pool.submit(()->tx.execute(s->training.buy(1L,purchase,1,"nectar")));
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(150,training.get(1L).value());
            assertEquals(0,balance(1L).compareTo(new BigDecimal("900")));
            jdbc.update("UPDATE spirit_pet SET training_value=10000 WHERE user_id=1");
            var c=pool.submit(()->tx.execute(s->training.reward(1L,1)));
            var d=pool.submit(()->tx.execute(s->training.reward(1L,1)));
            int amount=c.get(10,java.util.concurrent.TimeUnit.SECONDS).amount();
            assertEquals(amount,d.get(10,java.util.concurrent.TimeUnit.SECONDS).amount());
            assertEquals(0,balance(1L).compareTo(new BigDecimal("900").add(BigDecimal.valueOf(amount))));
        } finally { pool.shutdownNow(); }
    }
}
