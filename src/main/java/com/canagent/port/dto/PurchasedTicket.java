package com.canagent.port.dto;

import com.canagent.domain.lottery.GameType;

public record PurchasedTicket(GameType gameType, int roundNo, String numbers, int amount) {}
