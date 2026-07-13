package com.canagent.service.lottery.dto;

import java.util.List;

public record LottoDraw(int roundNo, List<Integer> numbers, int bonus, long firstWinAmount, boolean success) {}
