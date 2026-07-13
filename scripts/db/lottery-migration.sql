-- 복권 자동구매: 티켓 기록 테이블 (PostgreSQL)
-- 로컬/테스트는 JPA ddl-auto:update가 자동 생성. prod 적용은 배포 게이트(사용자 승인).
CREATE TABLE IF NOT EXISTS lottery_ticket (
    id              BIGSERIAL PRIMARY KEY,
    game_type       VARCHAR(16)  NOT NULL,   -- LOTTO645 / WIN720
    round_no        INTEGER      NOT NULL,   -- 회차
    numbers         VARCHAR(64)  NOT NULL,   -- 로또 "3,7,12,25,33,41" / 연금 "조:6자리"
    amount          INTEGER      NOT NULL,
    purchased_at    TIMESTAMP    NOT NULL,
    result_checked  BOOLEAN      NOT NULL DEFAULT FALSE,
    rank            INTEGER,                 -- null=미확인, 0=미당첨, 1..=등수(연금 보너스=8)
    prize_label     VARCHAR(64),
    winner          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_lottery_game_checked   ON lottery_ticket (game_type, result_checked);
CREATE INDEX IF NOT EXISTS idx_lottery_game_purchased ON lottery_ticket (game_type, purchased_at);
