CREATE TABLE account (
    id                       bigserial PRIMARY KEY,
    kind                     varchar(20)    NOT NULL,
    broker                   varchar(10)    NOT NULL,
    name                     varchar(50)    NOT NULL,
    toss_account_seq         bigint UNIQUE,
    isa_type                 varchar(20),
    isa_open_date            date,
    isa_maturity_date        date,
    prior_contribution_total numeric(20, 4) NOT NULL DEFAULT 0
);

COMMENT ON TABLE account IS '증권 계좌';
COMMENT ON COLUMN account.id IS '식별자';
COMMENT ON COLUMN account.kind IS '계좌 종류 (BROKERAGE 일반, ISA, PENSION_SAVINGS 연금저축)';
COMMENT ON COLUMN account.broker IS '증권사 (TOSS 토스증권, MIRAE 미래에셋증권)';
COMMENT ON COLUMN account.name IS '표시 이름';
COMMENT ON COLUMN account.toss_account_seq IS '토스 계좌 식별 키 (토스 계좌만)';
COMMENT ON COLUMN account.isa_type IS 'ISA 유형 (GENERAL 일반형, LOW_INCOME 서민형)';
COMMENT ON COLUMN account.isa_open_date IS 'ISA 가입일';
COMMENT ON COLUMN account.isa_maturity_date IS 'ISA 계약 만기일';
COMMENT ON COLUMN account.prior_contribution_total IS '올해 이전 누적 납입액 (원, ISA 이월 한도 계산용)';

CREATE TABLE holding (
    id             bigserial PRIMARY KEY,
    account_id     bigint         NOT NULL REFERENCES account (id),
    symbol         varchar(20)    NOT NULL,
    name           varchar(100)   NOT NULL,
    market_country varchar(2)     NOT NULL,
    currency       varchar(3)     NOT NULL,
    quantity       numeric(20, 6) NOT NULL,
    average_price  numeric(20, 4),
    updated_at     timestamptz    NOT NULL,
    UNIQUE (account_id, symbol)
);

COMMENT ON TABLE holding IS '계좌별 보유 종목';
COMMENT ON COLUMN holding.id IS '식별자';
COMMENT ON COLUMN holding.account_id IS '계좌';
COMMENT ON COLUMN holding.symbol IS '종목 코드 (국내 6자리, 미국 티커)';
COMMENT ON COLUMN holding.name IS '종목명';
COMMENT ON COLUMN holding.market_country IS '시장 (KR, US)';
COMMENT ON COLUMN holding.currency IS '통화 (KRW, USD)';
COMMENT ON COLUMN holding.quantity IS '보유 수량';
COMMENT ON COLUMN holding.average_price IS '평균 매수가 (종목 통화)';
COMMENT ON COLUMN holding.updated_at IS '갱신 시각';

CREATE TABLE ledger_entry (
    id              bigserial PRIMARY KEY,
    account_id      bigint         NOT NULL REFERENCES account (id),
    type            varchar(30)    NOT NULL,
    symbol          varchar(20),
    quantity        numeric(20, 6),
    amount          numeric(20, 4),
    tax             numeric(20, 4) NOT NULL DEFAULT 0,
    net_amount      numeric(20, 4),
    currency        varchar(3)     NOT NULL,
    exchange_rate   numeric(20, 6),
    settlement_date date           NOT NULL,
    is_cancellation boolean        NOT NULL DEFAULT false,
    source          varchar(10)    NOT NULL,
    raw             jsonb,
    memo            varchar(200)
);

CREATE INDEX ledger_entry_account_id_settlement_date_idx ON ledger_entry (account_id, settlement_date);
CREATE INDEX ledger_entry_account_id_symbol_type_idx ON ledger_entry (account_id, symbol, type);

COMMENT ON TABLE ledger_entry IS '거래 원장';
COMMENT ON COLUMN ledger_entry.id IS '식별자';
COMMENT ON COLUMN ledger_entry.account_id IS '계좌';
COMMENT ON COLUMN ledger_entry.type IS '거래 유형 (토스 transactionType, 종목 입금은 DIVIDEND)';
COMMENT ON COLUMN ledger_entry.symbol IS '종목 코드';
COMMENT ON COLUMN ledger_entry.quantity IS '수량 (매매·입출고만)';
COMMENT ON COLUMN ledger_entry.amount IS '거래금액 (종목 통화, 세전)';
COMMENT ON COLUMN ledger_entry.tax IS '세금 (종목 통화)';
COMMENT ON COLUMN ledger_entry.net_amount IS '정산 금액 (종목 통화, 세후)';
COMMENT ON COLUMN ledger_entry.currency IS '통화 (KRW, USD)';
COMMENT ON COLUMN ledger_entry.exchange_rate IS '원화 환율 (USD 결제 완료분)';
COMMENT ON COLUMN ledger_entry.settlement_date IS '결제일 (KST)';
COMMENT ON COLUMN ledger_entry.is_cancellation IS '취소 거래 여부';
COMMENT ON COLUMN ledger_entry.source IS '출처 (TOSS 동기화, MANUAL 수동 입력)';
COMMENT ON COLUMN ledger_entry.raw IS '토스 응답 원본';
COMMENT ON COLUMN ledger_entry.memo IS '메모';
