package com.hotel.hotelbookingsystem.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SpiritPetService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final java.util.random.RandomGenerator random;
    private static final List<String> ELEMENTS = List.of("METAL", "WOOD", "WATER", "FIRE", "EARTH");
    private static final List<String> NAMES = List.of("001", "芽枝鹿", "泡泡", "焰羽雀", "岩块鼹鼠");
    private static final int[] REWARDS = {5, 10, 15, 20, 30, 50};

    @org.springframework.beans.factory.annotation.Autowired
    public SpiritPetService(JdbcTemplate jdbc) { this(jdbc, new SecureRandom()); }

    public SpiritPetService(JdbcTemplate jdbc, java.util.random.RandomGenerator random) {
        this.jdbc = jdbc; this.random = random;
    }

    @Override
    public void run(ApplicationArguments args) {
        CultivationService.initialize(jdbc);
        PetTicketStore.initialize(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/spirit-pet.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        ensureColumn("spirit_pet", "rarity", "VARCHAR(3) NULL");
        ensureColumn("spirit_pet", "generation", "INT NOT NULL DEFAULT 1");
        ensureColumn("spirit_pet", "last_reset_request", "VARCHAR(36) NULL");
        ensureColumn("spirit_pet", "training_enabled", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn("spirit_pet", "training_value", "INT NOT NULL DEFAULT 0");
        ensureColumn("spirit_pet_draw", "generation", "INT NOT NULL DEFAULT 1");
        var legacyUsers = jdbc.queryForList("SELECT user_id FROM spirit_pet WHERE hatch_value >= 100 AND rarity IS NULL", Long.class);
        for (Long userId : legacyUsers) {
            jdbc.update("UPDATE spirit_pet SET rarity = ? WHERE user_id = ? AND rarity IS NULL", rollRarity(), userId);
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        boolean exists = Boolean.TRUE.equals(jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
                        return columns.next();
                    }
                }));
        if (!exists) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private String rollRarity() {
        // 50% hidden; ordinary outcomes retain C40%, B30%, A18%, S9%, SS3%.
        int roll = random.nextInt(1000);
        if (roll < 500) return "SSS";
        if (roll < 700) return "C";
        if (roll < 850) return "B";
        if (roll < 940) return "A";
        if (roll < 985) return "S";
        return "SS";
    }

    public record Pet(String element, String name, int hatchValue, boolean hatched,
                      int spinCount, int simulatedTotalCost, String rarity, boolean mutated, int generation, int freeTickets) {}
    public record SpinResult(int segmentIndex, int reward, int simulatedCost, Pet pet) {}

    private static final RowMapper<Pet> PET_MAPPER = (rs, row) -> {
        String element = rs.getString("element");
        int value = rs.getInt("hatch_value");
        int count = rs.getInt("spin_count");
        String rarity = rs.getString("rarity");
        boolean mutated = "SSS".equals(rarity);
        return new Pet(element, mutated ? "冰霜巨龙" : NAMES.get(ELEMENTS.indexOf(element)),
                value, value >= 100, count, count * 100, rarity, mutated, rs.getInt("generation"), 0);
    };

    private Pet enrich(Pet pet, Long userId) {
        PetTicketStore.ensureWallet(jdbc,userId);
        Integer total = jdbc.queryForObject("SELECT COALESCE(SUM(simulated_cost),0) FROM spirit_pet_draw WHERE user_id = ?",Integer.class,userId);
        Integer tickets = jdbc.queryForObject("SELECT tickets FROM vip_wallet WHERE user_id = ?",Integer.class,userId);
        return new Pet(pet.element(),pet.name(),pet.hatchValue(),pet.hatched(),pet.spinCount(),total,
                pet.rarity(),pet.mutated(),pet.generation(),tickets);
    }

    private void ensurePet(Long userId) {
        jdbc.update("INSERT INTO spirit_pet (user_id, element) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE user_id = user_id", userId, ELEMENTS.get(random.nextInt(5)));
    }

    @Transactional
    public Pet get(Long userId) {
        ensurePet(userId);
        return enrich(jdbc.queryForObject("SELECT * FROM spirit_pet WHERE user_id = ?", PET_MAPPER, userId), userId);
    }

    @Transactional
    public SpinResult spin(Long userId, String requestId) {
        return spin(userId, requestId, null);
    }

    @Transactional
    public SpinResult spin(Long userId, String requestId, Integer generation) {
        try {
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "抽奖请求编号无效");
        }
        ensurePet(userId);
        // Serialize draws per user. The same request never spends or awards twice.
        Pet pet = jdbc.queryForObject("SELECT * FROM spirit_pet WHERE user_id = ? FOR UPDATE", PET_MAPPER, userId);
        if (generation != null && generation != pet.generation()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "灵蛋已更换，请刷新后重试");
        }
        var previous = jdbc.query("SELECT segment_index, reward, simulated_cost FROM spirit_pet_draw WHERE user_id = ? AND request_id = ?",
                (rs, row) -> new SpinResult(rs.getInt("segment_index"), rs.getInt("reward"), rs.getInt("simulated_cost"), null), userId, requestId);
        if (!previous.isEmpty()) {
            Integer previousGeneration = jdbc.queryForObject("SELECT generation FROM spirit_pet_draw WHERE user_id = ? AND request_id = ?",
                    Integer.class, userId, requestId);
            if (previousGeneration != pet.generation()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "这是上一轮抽奖请求，请刷新后重试");
            }
            var result = previous.get(0);
            return new SpinResult(result.segmentIndex(), result.reward(), result.simulatedCost(), enrich(pet,userId));
        }
        if (pet.hatched()) throw new ResponseStatusException(HttpStatus.CONFLICT, "灵宠已孵化，无需继续抽奖");
        int index = random.nextInt(REWARDS.length);
        int reward = REWARDS[index];
        PetTicketStore.ensureWallet(jdbc,userId);
        int cost = jdbc.update("UPDATE vip_wallet SET tickets = tickets - 1 WHERE user_id = ? AND tickets > 0", userId) == 1 ? 0 : 100;
        jdbc.update("INSERT INTO spirit_pet_draw (user_id, request_id, segment_index, reward, generation, simulated_cost) VALUES (?, ?, ?, ?, ?, ?)",
                userId, requestId, index, reward, pet.generation(), cost);
        int progress = Math.min(100, pet.hatchValue() + reward);
        String rarity = progress >= 100 ? rollRarity() : null;
        jdbc.update("UPDATE spirit_pet SET hatch_value = ?, rarity = ?, spin_count = spin_count + 1 WHERE user_id = ?",
                progress, rarity, userId);
        return new SpinResult(index, reward, cost,
                enrich(jdbc.queryForObject("SELECT * FROM spirit_pet WHERE user_id = ?", PET_MAPPER, userId),userId));
    }

    @Transactional
    public Pet restart(Long userId, String requestId, Integer generation) {
        try {
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重新孵化请求编号无效");
        }
        ensurePet(userId);
        Pet pet = jdbc.queryForObject("SELECT * FROM spirit_pet WHERE user_id = ? FOR UPDATE", PET_MAPPER, userId);
        String lastRequest = jdbc.queryForObject("SELECT last_reset_request FROM spirit_pet WHERE user_id = ?", String.class, userId);
        if (requestId.equals(lastRequest)) return enrich(pet,userId);
        if (generation == null || generation != pet.generation() || !pet.hatched()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅可对当前已孵化灵宠重新孵化，请刷新后重试");
        }
        jdbc.update("UPDATE spirit_pet SET element = ?, hatch_value = 0, rarity = NULL, training_enabled = 0, training_value = 0, " +
                "generation = generation + 1, last_reset_request = ? WHERE user_id = ?",
                ELEMENTS.get(random.nextInt(5)), requestId, userId);
        return enrich(jdbc.queryForObject("SELECT * FROM spirit_pet WHERE user_id = ?", PET_MAPPER, userId), userId);
    }
}
