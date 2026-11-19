CREATE TABLE IF NOT EXISTS vip_wallet (
    user_id BIGINT PRIMARY KEY,
    tickets INT NOT NULL DEFAULT 0
) DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS vip_week_claim (
    user_id BIGINT NOT NULL,
    week_start DATE NOT NULL,
    vip_level INT NOT NULL,
    tickets INT NOT NULL,
    claimed_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, week_start)
) DEFAULT CHARSET=utf8mb4;
