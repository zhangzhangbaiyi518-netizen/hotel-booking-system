CREATE TABLE IF NOT EXISTS spirit_pet (
    user_id BIGINT PRIMARY KEY,
    element VARCHAR(10) NOT NULL,
    hatch_value INT NOT NULL DEFAULT 0,
    spin_count INT NOT NULL DEFAULT 0,
    rarity VARCHAR(3) NULL,
    generation INT NOT NULL DEFAULT 1,
    last_reset_request VARCHAR(36) NULL,
    training_enabled TINYINT NOT NULL DEFAULT 0,
    training_value INT NOT NULL DEFAULT 0,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS spirit_pet_draw (
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    segment_index INT NOT NULL,
    reward INT NOT NULL,
    generation INT NOT NULL DEFAULT 1,
    simulated_cost DECIMAL(10,2) NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_id)
) DEFAULT CHARSET=utf8mb4;
