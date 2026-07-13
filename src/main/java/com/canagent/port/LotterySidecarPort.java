package com.canagent.port;

import com.canagent.domain.lottery.GameType;
import com.canagent.port.dto.SidecarResult;

import java.util.List;

/**
 * 복권 구매 사이드카 경계 포트. 구현: PythonLotterySidecarAdapter(prod), 테스트는 Fake.
 * dhlottery 로그인·주문·예치금 조회 등 인증·취약 영역만 사이드카(파이썬)에 위임한다.
 */
public interface LotterySidecarPort {

    /** 지정 게임만 구매하고 결과·구매후잔액을 반환. */
    SidecarResult purchaseWeekly(List<GameType> games);

    /** 예치금 잔액만 조회(구매 없음). 테스트 Fake는 purchaseWeekly만 구현하면 되도록 default 제공. */
    default int getBalance() {
        throw new UnsupportedOperationException("getBalance() 미구현");
    }
}
