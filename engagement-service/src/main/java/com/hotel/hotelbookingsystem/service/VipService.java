package com.hotel.hotelbookingsystem.service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VipService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private static final long[] THRESHOLDS = {6,18,64,198,328,648,1888,5888,10888,100000};
    private static final int[] TICKETS = {1,2,3,5,8,12,20,30,50,100};
    @org.springframework.beans.factory.annotation.Autowired
    public VipService(JdbcTemplate jdbc) { this(jdbc, Clock.system(ZoneId.of("Asia/Singapore"))); }
    public VipService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public static void initialize(JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/vip.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
    }
    public static void ensureWallet(JdbcTemplate jdbc, Long userId) {
        jdbc.update("INSERT INTO vip_wallet (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id = user_id",userId);
    }
    @Override public void run(ApplicationArguments args) { initialize(jdbc); }
    public record Benefit(int level, BigDecimal threshold, int weeklyTickets) {}
    public record Status(int level, BigDecimal spending, BigDecimal currentThreshold, BigDecimal nextThreshold,
                         BigDecimal remaining, double progress, int weeklyTickets, int tickets,
                         boolean claimed, boolean canClaim, LocalDate weekStart, LocalDate nextReset,
                         List<Benefit> benefits) {}

    private LocalDate week() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore")))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
    public Status get(Long userId, BigDecimal spending) {
        ensureWallet(jdbc, userId);
        return status(userId, spending);
    }

    private Status status(Long userId, BigDecimal spending) {
        int level = 0;
        for (long threshold : THRESHOLDS) if (spending.compareTo(BigDecimal.valueOf(threshold)) >= 0) level++;
        BigDecimal current = level == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(THRESHOLDS[level-1]);
        BigDecimal next = level == 10 ? null : BigDecimal.valueOf(THRESHOLDS[level]);
        BigDecimal remaining = next == null ? BigDecimal.ZERO : next.subtract(spending);
        double progress = next == null ? 100 : spending.subtract(current).doubleValue() * 100 / next.subtract(current).doubleValue();
        LocalDate week = week();
        boolean claimed = jdbc.queryForObject("SELECT COUNT(*) FROM vip_week_claim WHERE user_id = ? AND week_start = ?",
                Integer.class,userId,week) > 0;
        int balance = jdbc.queryForObject("SELECT tickets FROM vip_wallet WHERE user_id = ?",Integer.class,userId);
        var benefits = new ArrayList<Benefit>();
        for (int i=0;i<10;i++) benefits.add(new Benefit(i+1,BigDecimal.valueOf(THRESHOLDS[i]),TICKETS[i]));
        return new Status(level,spending,current,next,remaining,Math.max(0,Math.min(100,progress)),
                level == 0 ? 0 : TICKETS[level-1],balance,claimed,level > 0 && !claimed,week,week.plusWeeks(1),benefits);
    }

    @Transactional
    public Status claim(Long userId, BigDecimal spending) {
        ensureWallet(jdbc,userId);
        jdbc.queryForObject("SELECT tickets FROM vip_wallet WHERE user_id = ? FOR UPDATE",Integer.class,userId);
        Status status = status(userId, spending);
        if (status.claimed()) return status;
        if (status.level() == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"达到 V1 后即可领取周福利");
        jdbc.update("INSERT INTO vip_week_claim (user_id,week_start,vip_level,tickets) VALUES (?,?,?,?)",
                userId,status.weekStart(),status.level(),status.weeklyTickets());
        jdbc.update("UPDATE vip_wallet SET tickets = tickets + ? WHERE user_id = ?",status.weeklyTickets(),userId);
        return status(userId, spending);
    }
}
