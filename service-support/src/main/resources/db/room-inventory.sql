CREATE TABLE IF NOT EXISTS room_inventory (
    room_type_id BIGINT PRIMARY KEY,
    total_count INT NOT NULL,
    reserved_count INT NOT NULL DEFAULT 0
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_reservation (
    order_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    room_type_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    token VARCHAR(36) NOT NULL
) DEFAULT CHARSET=utf8mb4;
