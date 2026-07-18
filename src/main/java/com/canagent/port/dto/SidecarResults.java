package com.canagent.port.dto;

import java.util.List;

/** 사이드카 `result` 서브커맨드 응답: 전체 미확인 티켓의 당첨/낙첨/미확정 판정. */
public record SidecarResults(boolean ok, List<TicketResult> results, List<SidecarError> errors) {}
