package com.canagent.port.dto;

import java.util.List;

public record SidecarResult(boolean ok, int balanceAfter,
                            List<PurchasedTicket> tickets, List<SidecarError> errors) {}
