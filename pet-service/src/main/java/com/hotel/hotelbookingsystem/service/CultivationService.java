package com.hotel.hotelbookingsystem.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CultivationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public record Supplement(String code,String name,int price,int gain,String description) {}
    private static final List<Supplement> SHOP=List.of(
            new Supplement("nectar","晨露甘泉",100,150,"温和滋养，开启相伴日常"),
            new Supplement("fruit","灵气果",200,325,"凝聚草木灵气的甜果"),
            new Supplement("pill","凝元丹",300,500,"补充元气，让关系更进一步"),
            new Supplement("ginseng","百年灵参",500,900,"精心培育的珍贵补品"),
            new Supplement("crystal","五行灵髓",800,1500,"五行灵宠皆可吸收的灵髓"),
            new Supplement("elixir","天元仙露",1000,2000,"珍稀仙露，带来深厚陪伴之力"));
    @org.springframework.beans.factory.annotation.Autowired
    public CultivationService(JdbcTemplate jdbc) { this(jdbc,Clock.system(ZoneId.of("Asia/Singapore"))); }
    public CultivationService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    public static void initialize(JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/account.sql")).execute(Objects.requireNonNull(jdbc.getDataSource()));
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore"))); }
    private void ensureWallet(Long userId) {
        jdbc.update("INSERT INTO account_wallet (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id",userId);
    }
    private BigDecimal lockBalance(Long userId) {
        ensureWallet(userId);
        return jdbc.queryForObject("SELECT balance FROM account_wallet WHERE user_id=? FOR UPDATE",BigDecimal.class,userId);
    }
    private void validId(String id) {
        try { if(id==null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请求编号无效"); }
    }
    private Map<String,Object> lockPet(Long userId,Integer generation) {
        var pets=jdbc.queryForList("SELECT * FROM spirit_pet WHERE user_id=? FOR UPDATE",userId);
        if(pets.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先孵化灵宠");
        var pet=pets.get(0);
        if(generation==null || ((Number)pet.get("generation")).intValue()!=generation || ((Number)pet.get("hatch_value")).intValue()<100)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先孵化当前灵宠，或刷新页面");
        return pet;
    }
    public record Wallet(BigDecimal balance,List<Map<String,Object>> transactions) {}
    public Wallet wallet(Long userId) {
        ensureWallet(userId);
        return new Wallet(jdbc.queryForObject("SELECT balance FROM account_wallet WHERE user_id=?",BigDecimal.class,userId),
                jdbc.queryForList("SELECT type,amount,balance_after,created_time FROM account_transaction WHERE user_id=? ORDER BY created_time DESC, request_id LIMIT 10",userId));
    }
    @Transactional public Wallet recharge(Long userId,String requestId,BigDecimal amount) {
        validId(requestId);
        if(amount==null || amount.compareTo(BigDecimal.ONE)<0 || amount.compareTo(new BigDecimal("100000"))>0 || amount.stripTrailingZeros().scale()>2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"充值金额为1至100000元，最多两位小数");
        BigDecimal balance=lockBalance(userId);
        var previous=jdbc.queryForList("SELECT type,amount FROM account_transaction WHERE user_id=? AND request_id=?",userId,requestId);
        if(!previous.isEmpty()) {
            if(!"RECHARGE".equals(previous.get(0).get("type")) || ((BigDecimal)previous.get(0).get("amount")).compareTo(amount)!=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"请求编号已用于其他操作");
            return wallet(userId);
        }
        if(balance.add(amount).compareTo(new BigDecimal("999999999999.99"))>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"余额超出限额");
        ledger(userId,requestId,"RECHARGE",amount,balance.add(amount),null,0,null);
        return wallet(userId);
    }
    private void ledger(Long userId,String id,String type,BigDecimal amount,BigDecimal after,String item,int gain,Integer generation) {
        jdbc.update("UPDATE account_wallet SET balance=? WHERE user_id=?",after,userId);
        jdbc.update("INSERT INTO account_transaction (user_id,request_id,type,amount,balance_after,item_code,gain,generation) VALUES (?,?,?,?,?,?,?,?)",
                userId,id,type,amount,after,item,gain,generation);
    }
    public record Training(boolean hatched,boolean enabled,int value,String stage,int generation,
                           BigDecimal balance,boolean rewardClaimed,List<Supplement> shop) {}
    public Training get(Long userId) {
        ensureWallet(userId);
        var rows=jdbc.queryForList("SELECT hatch_value,training_enabled,training_value,generation FROM spirit_pet WHERE user_id=?",userId);
        Map<String,Object> pet=rows.isEmpty()?Map.of():rows.get(0);
        int value=((Number)pet.getOrDefault("training_value",0)).intValue();
        String stage=value>=10000?"形影不离":value>=6000?"友好":value>=3000?"熟悉":value>=1000?"相识":"陌生";
        boolean enabled=((Number)pet.getOrDefault("training_enabled",0)).intValue()==1;
        boolean claimed=jdbc.queryForObject("SELECT COUNT(*) FROM account_transaction WHERE user_id=? AND request_id=?",Integer.class,userId,"reward-"+today())>0;
        return new Training(((Number)pet.getOrDefault("hatch_value",0)).intValue()>=100,enabled,value,stage,
                ((Number)pet.getOrDefault("generation",0)).intValue(),
                jdbc.queryForObject("SELECT balance FROM account_wallet WHERE user_id=?",BigDecimal.class,userId),claimed,enabled?SHOP:List.of());
    }
    @Transactional public Training start(Long userId,Integer generation) {
        lockPet(userId,generation);
        jdbc.update("UPDATE spirit_pet SET training_enabled=1 WHERE user_id=?",userId);
        return get(userId);
    }
    @Transactional public Training buy(Long userId,String requestId,Integer generation,String code) {
        validId(requestId); var pet=lockPet(userId,generation);
        if(((Number)pet.get("training_enabled")).intValue()!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"请先进入培养阶段");
        var item=SHOP.stream().filter(s->s.code().equals(code)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"补品不存在"));
        BigDecimal balance=lockBalance(userId);
        var previous=jdbc.queryForList("SELECT type,item_code,generation FROM account_transaction WHERE user_id=? AND request_id=?",userId,requestId);
        if(!previous.isEmpty()) {
            var record=previous.get(0);
            if(!"PET_SUPPLEMENT".equals(record.get("type")) || !item.code().equals(record.get("item_code")) || ((Number)record.get("generation")).intValue()!=generation)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"请求编号已用于其他操作");
            return get(userId);
        }
        int value=((Number)pet.get("training_value")).intValue();
        if(value>=10000) throw new ResponseStatusException(HttpStatus.CONFLICT,"培养度已满，无需购买补品");
        BigDecimal price=BigDecimal.valueOf(item.price());
        if(balance.compareTo(price)<0) throw new ResponseStatusException(HttpStatus.CONFLICT,"余额不足，请到个人中心充值");
        int gain=Math.min(item.gain(),10000-value);
        jdbc.update("UPDATE spirit_pet SET training_value=training_value+? WHERE user_id=?",gain,userId);
        ledger(userId,requestId,"PET_SUPPLEMENT",price.negate(),balance.subtract(price),code,gain,generation);
        return get(userId);
    }
    public record Reward(int amount,Training training) {}
    @Transactional public Reward reward(Long userId,Integer generation) {
        var pet=lockPet(userId,generation);
        if(((Number)pet.get("training_enabled")).intValue()!=1 || ((Number)pet.get("training_value")).intValue()<10000)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"达到形影不离后才能领取奖励");
        BigDecimal balance=lockBalance(userId);
        String id="reward-"+today();
        var previous=jdbc.queryForList("SELECT amount FROM account_transaction WHERE user_id=? AND request_id=?",userId,id);
        if(!previous.isEmpty()) return new Reward(((BigDecimal)previous.get(0).get("amount")).intValueExact(),get(userId));
        int amount=100+random.nextInt(901);
        ledger(userId,id,"PET_REWARD",BigDecimal.valueOf(amount),balance.add(BigDecimal.valueOf(amount)),null,0,generation);
        return new Reward(amount,get(userId));
    }
}
