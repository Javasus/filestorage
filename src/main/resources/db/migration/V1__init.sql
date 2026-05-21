CREATE TABLE IF NOT EXISTS users
(
    id            INT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    salt          VARCHAR(255) NOT NULL,
    status        VARCHAR(50)  NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER'
);

CREATE TABLE IF NOT EXISTS files
(
    id       INT PRIMARY KEY AUTO_INCREMENT,
    name     VARCHAR(255) NOT NULL,
    location VARCHAR(500) NOT NULL,
    status   VARCHAR(50)  NOT NULL
);

CREATE TABLE IF NOT EXISTS events
(
    id        INT PRIMARY KEY AUTO_INCREMENT,
    user_id   INT         NOT NULL,
    file_id   INT         NOT NULL,
    status    VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE CASCADE
);