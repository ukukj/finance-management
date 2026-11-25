CREATE TABLE sync_run (
    id             bigserial PRIMARY KEY,
    trigger_type   varchar(10)  NOT NULL,
    status         varchar(10)  NOT NULL,
    started_at     timestamptz  NOT NULL,
    finished_at    timestamptz,
    failure_reason varchar(500)
);

COMMENT ON TABLE sync_run IS '토스 동기화 실행 기록';
COMMENT ON COLUMN sync_run.id IS '식별자';
COMMENT ON COLUMN sync_run.trigger_type IS '실행 계기 (SCHEDULED 새벽 자동, MANUAL 수동)';
COMMENT ON COLUMN sync_run.status IS '결과 (RUNNING 실행 중, SUCCEEDED 성공, FAILED 실패, SKIPPED 겹쳐서 건너뜀)';
COMMENT ON COLUMN sync_run.started_at IS '시작 시각';
COMMENT ON COLUMN sync_run.finished_at IS '종료 시각';
COMMENT ON COLUMN sync_run.failure_reason IS '실패 사유';

CREATE TABLE fx_rate (
    currency varchar(3) PRIMARY KEY,
    krw_rate numeric(20, 6) NOT NULL,
    as_of    timestamptz    NOT NULL
);

COMMENT ON TABLE fx_rate IS '최신 환율';
COMMENT ON COLUMN fx_rate.currency IS '통화';
COMMENT ON COLUMN fx_rate.krw_rate IS '1단위당 원화 (토스 참고 환율)';
COMMENT ON COLUMN fx_rate.as_of IS '조회 시각';
