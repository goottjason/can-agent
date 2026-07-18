package com.canagent.port.dto;

import com.canagent.domain.lottery.GameType;

/**
 * 사이드카 MY 당첨내역 스크래핑 결과 1건.
 * status ∈ {"WIN","LOSE","PENDING"} (PENDING=추첨중/미확정). winAmount=당첨금(원, 낙첨 0).
 */
public record TicketResult(GameType gameType, int roundNo, String status, long winAmount, String drawDate) {}
