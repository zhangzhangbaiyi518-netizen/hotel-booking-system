package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.service.SpiritPetService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SpiritPetServiceTest {
    private JdbcTemplate jdbc;
    private SpiritPetService pets;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        pets = new SpiritPetService(jdbc); pets.run(null);
    }

    @Test
    void eggIsAssignedOnceAndEachElementMapsToItsPet() {
        var first = pets.get(1L);
        assertEquals(first.element(), new SpiritPetService(jdbc).get(1L).element());
        assertEquals(0, first.hatchValue()); assertFalse(first.hatched());
        var elements = List.of("METAL", "WOOD", "WATER", "FIRE", "EARTH");
        var names = List.of("001", "芽枝鹿", "泡泡", "焰羽雀", "岩块鼹鼠");
        for (int i = 0; i < elements.size(); i++) {
            jdbc.update("UPDATE spirit_pet SET element = ?, hatch_value = 100 WHERE user_id = 1", elements.get(i));
            assertEquals(names.get(i), pets.get(1L).name()); assertTrue(pets.get(1L).hatched());
        }
    }

    @Test
    void retriesDoNotSpendOrAwardTwiceAndOtherUsersRemainSeparate() {
        String request = UUID.randomUUID().toString();
        var result = pets.spin(1L, request);
        var repeat = pets.spin(1L, request);
        assertEquals(result, repeat);
        assertTrue(List.of(5,10,15,20,30,50).contains(result.reward()));
        assertEquals(List.of(5,10,15,20,30,50).get(result.segmentIndex()), result.reward());
        assertEquals(100, repeat.pet().simulatedTotalCost());
        assertEquals(0, pets.get(2L).hatchValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM spirit_pet_draw WHERE user_id = 1", Integer.class));
    }

    @Test
    void progressCapsAt100AndNoMoreDrawsAfterHatching() {
        pets.get(1L); jdbc.update("UPDATE spirit_pet SET hatch_value = 99 WHERE user_id = 1");
        String request = UUID.randomUUID().toString();
        var result = pets.spin(1L, request);
        assertEquals(100, result.pet().hatchValue()); assertTrue(result.pet().hatched());
        assertEquals(result, pets.spin(1L, request));
        assertThrows(ResponseStatusException.class, () -> pets.spin(1L, UUID.randomUUID().toString()));
        assertEquals(1, pets.get(1L).spinCount());
        assertThrows(ResponseStatusException.class, () -> pets.spin(1L, "invalid"));
    }

    @Test
    void simultaneousRetriesUseOneDrawWithTransactionLock() throws Exception {
        pets.get(1L);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String id = UUID.randomUUID().toString();
        try {
            var a = pool.submit(() -> tx.execute(status -> pets.spin(1L,id)));
            var b = pool.submit(() -> tx.execute(status -> pets.spin(1L,id)));
            assertEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS), b.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, pets.get(1L).spinCount());
        } finally { pool.shutdownNow(); }
    }

    private SpiritPetService withRoll(int roll) {
        return new SpiritPetService(jdbc, new java.util.Random() {
            @Override public int nextInt(int bound) { return bound == 1000 ? roll : 0; }
        });
    }

    @Test
    void fiftyPercentMutationAndAllOrdinaryRankBoundariesAreAssignedOnlyAtHatch() {
        int[] rolls = {0,499,500,699,700,849,850,939,940,984,985,999};
        String[] ranks = {"SSS","SSS","C","C","B","B","A","A","S","S","SS","SS"};
        for (int i = 0; i < rolls.length; i++) {
            long userId = i + 10L;
            var service = withRoll(rolls[i]);
            assertNull(service.get(userId).rarity());
            jdbc.update("UPDATE spirit_pet SET hatch_value = 99 WHERE user_id = ?", userId);
            String request = UUID.randomUUID().toString();
            var result = service.spin(userId, request);
            assertEquals(ranks[i], result.pet().rarity());
            assertEquals(i < 2, result.pet().mutated());
            assertEquals(i < 2 ? "冰霜巨龙" : "001", result.pet().name());
            assertEquals(result, service.spin(userId, request));
            assertEquals(ranks[i], new SpiritPetService(jdbc).get(userId).rarity());
        }
    }

    @Test
    void legacyHatchedPetsReceiveOnePersistentRatingOnUpgrade() {
        pets.get(1L);
        jdbc.update("UPDATE spirit_pet SET hatch_value = 100 WHERE user_id = 1");
        jdbc.execute("ALTER TABLE spirit_pet DROP COLUMN rarity");
        var upgrade = withRoll(0);
        upgrade.run(null);
        assertEquals("SSS", upgrade.get(1L).rarity());
        assertEquals("冰霜巨龙", upgrade.get(1L).name());
        withRoll(999).run(null);
        assertEquals("SSS", upgrade.get(1L).rarity());
    }

    @Test
    void restartReplacesPetAndClearsProgressButPreservesSpendingAndRequestHistory() {
        pets.get(1L); jdbc.update("UPDATE spirit_pet SET hatch_value = 99 WHERE user_id = 1");
        String draw = UUID.randomUUID().toString();
        var hatched = withRoll(0).spin(1L, draw, 1).pet();
        assertTrue(hatched.mutated());
        String reset = UUID.randomUUID().toString();
        var egg = pets.restart(1L, reset, 1);
        assertEquals(2, egg.generation()); assertEquals(0, egg.hatchValue());
        assertFalse(egg.hatched()); assertFalse(egg.mutated()); assertNull(egg.rarity());
        assertEquals(hatched.spinCount(), egg.spinCount());
        assertEquals(hatched.simulatedTotalCost(), egg.simulatedTotalCost());
        assertEquals(egg, pets.restart(1L, reset, 1));
        assertEquals(egg, new SpiritPetService(jdbc).get(1L));
        assertThrows(ResponseStatusException.class, () -> pets.spin(1L, draw, 2));
        assertThrows(ResponseStatusException.class, () -> pets.spin(1L, UUID.randomUUID().toString(), 1));
        var next = pets.spin(1L, UUID.randomUUID().toString(), 2);
        assertEquals(2, next.pet().spinCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM spirit_pet_draw WHERE user_id = 1", Integer.class));
    }

    @Test
    void restartRejectsUnhatchedAndStaleRequestsAndLeavesOtherUsersAlone() {
        var other = pets.get(2L);
        assertThrows(ResponseStatusException.class, () -> pets.restart(1L, UUID.randomUUID().toString(), 1));
        jdbc.update("UPDATE spirit_pet SET hatch_value = 100, rarity = 'C' WHERE user_id = 1");
        assertThrows(ResponseStatusException.class, () -> pets.restart(1L, "bad-id", 1));
        assertThrows(ResponseStatusException.class, () -> pets.restart(1L, UUID.randomUUID().toString(), 99));
        pets.restart(1L, UUID.randomUUID().toString(), 1);
        assertEquals(other, pets.get(2L));
        assertThrows(ResponseStatusException.class, () -> pets.restart(1L, UUID.randomUUID().toString(), 1));
    }

    @Test
    void concurrentFreeSpinRetriesConsumeOneCouponAndDoNotCountAsSpending() throws Exception {
        pets.get(1L);
        jdbc.update("UPDATE vip_wallet SET tickets = 2 WHERE user_id = 1");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String request = UUID.randomUUID().toString();
        try {
            var a = pool.submit(() -> tx.execute(status -> pets.spin(1L, request, 1)));
            var b = pool.submit(() -> tx.execute(status -> pets.spin(1L, request, 1)));
            assertEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, pets.get(1L).freeTickets());
            assertEquals(1, pets.get(1L).spinCount());
            assertEquals(0, new com.hotel.hotelbookingsystem.service.PetSpendingService(jdbc).spending(1L).signum());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedDrawTransactionRollsBackCouponAndPetProgressTogether() {
        pets.get(1L);
        jdbc.update("UPDATE vip_wallet SET tickets = 1 WHERE user_id = 1");
        var before = pets.get(1L);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            pets.spin(1L, UUID.randomUUID().toString(), 1);
            status.setRollbackOnly();
        });
        assertEquals(before, pets.get(1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM spirit_pet_draw", Integer.class));
    }
}
