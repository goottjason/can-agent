package com.canagent.service.toss;

import com.canagent.port.BrokerPort;
import com.canagent.service.KoreaInvestmentApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8(broker 전환) 배선 검증: toss.broker.enabled에 따라 주입되는 BrokerPort가 바뀐다.
 * <ul>
 *   <li>기본(test 프로필, toss.broker.enabled=false): KIS(KoreaInvestmentApiClient) 단독 → 그게 주입된다.</li>
 *   <li>toss.broker.enabled=true: TossBrokerAdapter가 @Primary로 주입된다(KIS는 폴백).</li>
 * </ul>
 * 이로써 대시보드 "증권사=토스" 정본이 실제 배선과 일치함을 회귀 보증한다.
 */
class BrokerPortWiringTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("toss.broker.enabled=false(기본): BrokerPort는 KIS 폴백")
    class BrokerDisabled {
        @Autowired
        private BrokerPort brokerPort;

        @Test
        @DisplayName("주입 BrokerPort는 KoreaInvestmentApiClient")
        void brokerIsKis() {
            assertThat(brokerPort).isInstanceOf(KoreaInvestmentApiClient.class);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = "toss.broker.enabled=true")
    @DisplayName("toss.broker.enabled=true: BrokerPort는 TossBrokerAdapter(@Primary)")
    class BrokerEnabled {
        @Autowired
        private BrokerPort brokerPort;

        @Test
        @DisplayName("주입 BrokerPort는 TossBrokerAdapter")
        void brokerIsToss() {
            assertThat(brokerPort).isInstanceOf(TossBrokerAdapter.class);
        }
    }
}
