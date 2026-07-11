package com.canagent.worker;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.repository.PortfolioRepository;
import com.canagent.port.BrokerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true")
public class PortfolioScheduler {

    private static final Logger log = LoggerFactory.getLogger(PortfolioScheduler.class);

    private final PortfolioRepository portfolioRepository;
    private final BrokerPort brokerPort;

    public PortfolioScheduler(PortfolioRepository portfolioRepository,
                              BrokerPort brokerPort) {
        this.portfolioRepository = portfolioRepository;
        this.brokerPort = brokerPort;
    }

    // 장중 현재가 갱신: ET 정규장 시간대 5분마다(09:00~09:29 틱은 실질 무해 — 종가/현재가 표시 갱신용).
    @Scheduled(cron = "${trading.scheduler.price-cron:0 */5 9-15 * * MON-FRI}", zone = "America/New_York")
    @Transactional
    public void updatePortfolioPrices() {
        log.debug("포트폴리오 현재가 갱신 시작");

        List<Portfolio> activePortfolios = portfolioRepository.findByActiveTrue();
        int updatedCount = 0;

        for (Portfolio portfolio : activePortfolios) {
            try {
                // P6: 포트가 broker-중립 BigDecimal 현재가를 직접 반환(실패/0은 skip). USD 센트 무손실.
                BigDecimal currentPrice = brokerPort.getCurrentPrice(portfolio.getStock().getCode());
                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    portfolio.updateCurrentPrice(currentPrice);
                    portfolioRepository.save(portfolio);
                    updatedCount++;
                }
            } catch (Exception e) {
                log.error("현재가 갱신 실패: {} ({}) - {}",
                        portfolio.getStock().getName(),
                        portfolio.getStock().getCode(),
                        e.getMessage());
            }
        }

        log.debug("포트폴리오 현재가 갱신 완료: {}건 갱신", updatedCount);
    }

    // 종가 갱신: ET 정규장 마감 16:00에 최종가 확정.
    @Scheduled(cron = "${trading.scheduler.close-price-cron:0 0 16 * * MON-FRI}", zone = "America/New_York")
    @Transactional
    public void updateClosePrices() {
        log.info("장 마감 후 포트폴리오 종가 갱신 시작");

        List<Portfolio> activePortfolios = portfolioRepository.findByActiveTrue();
        int updatedCount = 0;

        for (Portfolio portfolio : activePortfolios) {
            try {
                // P6: 포트가 broker-중립 BigDecimal 종가를 직접 반환(실패/0은 skip). USD 센트 무손실.
                BigDecimal currentPrice = brokerPort.getCurrentPrice(portfolio.getStock().getCode());
                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    portfolio.updateCurrentPrice(currentPrice);
                    portfolioRepository.save(portfolio);
                    updatedCount++;
                }
            } catch (Exception e) {
                log.error("종가 갱신 실패: {} ({}) - {}",
                        portfolio.getStock().getName(),
                        portfolio.getStock().getCode(),
                        e.getMessage());
            }
        }

        log.info("장 마감 후 포트폴리오 종가 갱신 완료: {}건 갱신", updatedCount);
    }
}
