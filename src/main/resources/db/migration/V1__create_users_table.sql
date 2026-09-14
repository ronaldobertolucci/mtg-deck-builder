CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    date_of_birth DATE         NOT NULL,
    password      VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT true
);

CREATE INDEX idx_users_email ON users (email);