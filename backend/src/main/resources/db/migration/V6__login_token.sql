CREATE TABLE login_attempt (
    id           smallint PRIMARY KEY CHECK (id = 1),
    failed_count int      NOT NULL,
    locked_until timestamptz
);

COMMENT ON TABLE login_attempt IS '로그인 실패 잠금 상태';
COMMENT ON COLUMN login_attempt.id IS '식별자 (항상 1)';
COMMENT ON COLUMN login_attempt.failed_count IS '연속 실패 횟수';
COMMENT ON COLUMN login_attempt.locked_until IS '잠금 해제 시각';

INSERT INTO login_attempt (id, failed_count, locked_until) VALUES (1, 0, NULL);

CREATE TABLE refresh_token (
    id         bigserial PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

COMMENT ON TABLE refresh_token IS '로그인 유지 토큰';
COMMENT ON COLUMN refresh_token.id IS '식별자';
COMMENT ON COLUMN refresh_token.token_hash IS '토큰 해시 (HMAC-SHA256 16진수)';
COMMENT ON COLUMN refresh_token.expires_at IS '만료 시각';
COMMENT ON COLUMN refresh_token.created_at IS '발급 시각';
