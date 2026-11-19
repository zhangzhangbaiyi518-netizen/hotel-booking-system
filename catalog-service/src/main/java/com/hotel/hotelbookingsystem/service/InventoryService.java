package com.hotel.hotelbookingsystem.service;

import com.hotel.hotelbookingsystem.inventory.InventoryLedger;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hotel.hotelbookingsystem.inventory.InventoryLock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryService {
    private final InventoryLedger ledger;
    private final InventoryLock lock;
    private final TransactionTemplate transactions;

    public InventoryService(JdbcTemplate jdbc, @Value("${hotel.inventory.initial-stock:10}") int stock,
                            InventoryLock lock, PlatformTransactionManager transactionManager) {
        this.ledger = new InventoryLedger(jdbc, stock);
        this.lock = lock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @jakarta.annotation.PostConstruct
    public void initialize() {
        ledger.initialize();
    }

    @Transactional
    public RoomInventory inventory(Long roomTypeId) { return ledger.inventory(roomTypeId); }

    public void reserve(Long userId, String orderId, Long roomTypeId) {
        // Acquire before starting SQL work; finish the local AT branch before unlocking.
        lock.execute(roomTypeId, () -> transactions.executeWithoutResult(
                status -> ledger.reserve(userId, orderId, roomTypeId)));
    }

    @Transactional
    public void importHeld(Long userId, String orderId, Long roomTypeId, Integer total) {
        ledger.importReservation(userId, orderId, roomTypeId, total);
    }

    @Transactional
    public void release(Long userId, String orderId) { ledger.release(userId, orderId); }
}
