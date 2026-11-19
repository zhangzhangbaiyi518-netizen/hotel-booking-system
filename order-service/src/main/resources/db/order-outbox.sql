CREATE TABLE IF NOT EXISTS order_outbox (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    payload LONGTEXT NOT NULL,
    deadline_at DATETIME(3) NULL,
    global_xid VARCHAR(128) NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    owner VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    INDEX idx_order_outbox_pending (state, next_attempt_at, lease_until)
) DEFAULT CHARSET=utf8mb4;
