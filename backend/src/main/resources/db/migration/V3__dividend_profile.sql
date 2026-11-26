CREATE TABLE dividend_profile (
    id         bigserial PRIMARY KEY,
    account_id bigint         NOT NULL REFERENCES account (id),
    symbol     varchar(20)    NOT NULL,
    per_share  numeric(20, 6) NOT NULL,
    currency   varchar(3)     NOT NULL,
    pay_months varchar(40)    NOT NULL,
    net_ratio  numeric(10, 6) NOT NULL,
    source     varchar(10)    NOT NULL,
    updated_at timestamptz    NOT NULL,
    UNIQUE (account_id, symbol)
);

COMMENT ON TABLE dividend_profile IS '종목별 배당 정보';
COMMENT ON COLUMN dividend_profile.id IS '식별자';
COMMENT ON COLUMN dividend_profile.account_id IS '계좌';
COMMENT ON COLUMN dividend_profile.symbol IS '종목 코드';
COMMENT ON COLUMN dividend_profile.per_share IS '주당 배당 (종목 통화, 세전)';
COMMENT ON COLUMN dividend_profile.currency IS '통화 (KRW, USD)';
COMMENT ON COLUMN dividend_profile.pay_months IS '지급 월 목록 (쉼표 구분, 예 3,6,9,12)';
COMMENT ON COLUMN dividend_profile.net_ratio IS '세후/세전 비율 (수동 입력은 1)';
COMMENT ON COLUMN dividend_profile.source IS '출처 (AUTO 이력 추정, MANUAL 수동 입력)';
COMMENT ON COLUMN dividend_profile.updated_at IS '갱신 시각';
