package com.canagent.worker;

import com.canagent.domain.portfolio.Portfolio;
import com.canagent.repository.PortfolioRepository;
import com.canagent.service.KoreaInvestmentApiClient;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
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
    private final KoreaInvestmentApiClient koreaInvestmentApiClient;

    public PortfolioScheduler(PortfolioRepository portfolioRepository,
                              KoreaInvestmentApiClient koreaInvestmentApiClient) {
        this.portfolioRepository = portfolioRepository;
        this.koreaInvestmentApiClient = koreaInvestmentApiClient;
    }

    @Scheduled(cron = "${trading.scheduler.price-cron:0 */5 9-15 * * MON-FRI}")
    @Transactional
    public void updatePortfolioPrices() {
        log.debug("포트폴리오 현재가 갱신 시작");

        List<Portfolio> activePortfolios = portfolioRepository.findByActiveTrue();
        int updatedCount = 0;

        for (Portfolio portfolio : activePortfolios) {
            try {
                KoreaInvestmentPriceResponse response = koreaInvestmentApiClient
                        .getCurrentPrice(portfolio.getStock().getCode());

                if (response != null && response.isSuccess()) {
                    int currentPriceInt = response.getCurrentPrice();
                    if (currentPriceInt > 0) {
                        BigDecimal currentPrice = new BigDecimal(currentPriceInt);
                        portfolio.updateCurrentPrice(currentPrice);
                        portfolioRepository.save(portfolio);
                        updatedCount++;
                    }
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

    @Scheduled(cron = "${trading.scheduler.close-price-cron:0 0 16 * * MON-FRI}")
    @Transactional
    public void updateClosePrices() {
        log.info("장 마감 후 포트폴리오 종가 갱신 시작");

        List<Portfolio> activePortfolios = portfolioRepository.findByActiveTrue();
        int updatedCount = 0;

        for (Portfolio portfolio : activePortfolios) {
            try {
                KoreaInvestmentPriceResponse response = koreaInvestmentApiClient
                        .getCurrentPrice(portfolio.getStock().getCode());

                if (response != null && response.isSuccess()) {
                    int currentPriceInt = response.getCurrentPrice();
                    if (currentPriceInt > 0) {
                        BigDecimal currentPrice = new BigDecimal(currentPriceInt);
                        portfolio.updateCurrentPrice(currentPrice);
                        portfolioRepository.save(portfolio);
                        updatedCount++;
                    }
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
