package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.config.KoreaInvestmentTokenProvider;
import com.canagent.port.BrokerPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.port.dto.OrderStatus;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 한국투자증권 브로커 어댑터 — {@link BrokerPort} 국내(정수·KRW) 구현.
 *
 * <p>P6(미국 대전환): 포트 계약이 broker-중립 값타입으로 재설계됨에 따라, KIS DTO를
 * {@link OrderResult}/{@link BrokerBalance}/{@link OrderStatus}로 <b>어댑터 내부에서 매핑</b>한다.
 * REST 조립·KIS DTO 파싱은 종전과 동일(회귀 없음). P8(broker 전환)에서 {@code @Primary}를 제거해
 * <b>폴백 빈</b>이 됐다: {@code toss.broker.enabled=true}면 {@code TossBrokerAdapter}(@Primary)가 주입되고,
 * 꺼지면(예: test 프로필) 이 빈이 단독 BrokerPort로 선택된다.
 *
 * <p>국내 경로는 <b>정수 지정가({@code Limit})만 지원</b>한다. {@code Notional}(금액 시장가)은 미국 소수 매수 전용이라
 * KIS에선 사유를 담아 실패 반환한다(조용실패 금지). 가격 0인 {@code Limit}은 종전과 동일하게 시장가로 처리한다.
 */
@Service
public class KoreaInvestmentApiClient implements BrokerPort {

    private static final Logger log = LoggerFactory.getLogger(KoreaInvestmentApiClient.class);

    private static final String ORDER_PATH = "/uapi/domestic-stock/v1/trading/order-cash";
    private static final String BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance";
    private static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String HASHKEY_PATH = "/uapi/hashkey";

    // 현금주문 tr_id는 조회(R)가 아니라 주문(U) 접미사여야 한다.
    // R을 POST 주문 엔드포인트로 보내면 게이트웨이가 라우트를 못 찾아 EGW00202("GW라우팅 중 오류")를 반환한다.
    // 실전/모의는 접두사(TTTC/VTTC)가 다르므로 isReal로 분기한다.
    private static final String BUY_TR_ID_REAL = "TTTC0802U";
    private static final String BUY_TR_ID_MOCK = "VTTC0802U";
    private static final String SELL_TR_ID_REAL = "TTTC0801U";
    private static final String SELL_TR_ID_MOCK = "VTTC0801U";
    private static final String BALANCE_TR_ID_REAL = "TTTC8434R";
    private static final String BALANCE_TR_ID_MOCK = "VTTC8434R";
    private static final String PRICE_TR_ID = "FHKST01010100"; // 실전·모의 공통

    private String buyTrId() {
        return apiConfig.getKoreaInvestment().isReal() ? BUY_TR_ID_REAL : BUY_TR_ID_MOCK;
    }

    private String sellTrId() {
        return apiConfig.getKoreaInvestment().isReal() ? SELL_TR_ID_REAL : SELL_TR_ID_MOCK;
    }

    private String balanceTrId() {
        return apiConfig.getKoreaInvestment().isReal() ? BALANCE_TR_ID_REAL : BALANCE_TR_ID_MOCK;
    }

    private final RestTemplate restTemplate;
    private final ApiConfig apiConfig;
    private final KoreaInvestmentTokenProvider tokenProvider;

    public KoreaInvestmentApiClient(RestTemplate restTemplate,
                                    ApiConfig apiConfig,
                                    KoreaInvestmentTokenProvider tokenProvider) {
        this.restTemplate = restTemplate;
        this.apiConfig = apiConfig;
        this.tokenProvider = tokenProvider;
    }

    // ========== BrokerPort (broker-중립) ==========

    @Override
    public OrderResult placeBuy(String symbol, OrderSpec spec) {
        OrderSpec.Limit limit = toDomesticLimit(symbol, spec, "매수");
        if (limit == null) {
            return OrderResult.failure("KIS 매수: 유효한 수량/가격 산출 실패(금액기반→정수주 변환 불가)");
        }
        return toOrderResult(executeOrder(symbol, "01", limit, buyTrId()));
    }

    @Override
    public OrderResult placeSell(String symbol, OrderSpec spec) {
        OrderSpec.Limit limit = toDomesticLimit(symbol, spec, "매도");
        if (limit == null) {
            return OrderResult.failure("KIS 매도: 유효한 수량/가격 산출 실패(금액기반→정수주 변환 불가)");
        }
        return toOrderResult(executeOrder(symbol, "02", limit, sellTrId()));
    }

    @Override
    public OrderResult modify(String orderId, OrderSpec spec) {
        // 국내 정정 경로는 이번 단계 범위 밖(현 매매 루프는 정정을 쓰지 않는다). 사유를 담아 실패 반환.
        return OrderResult.failure("KIS 국내 주문 정정 미구현");
    }

    @Override
    public OrderResult cancel(String orderId) {
        return OrderResult.failure("KIS 국내 주문 취소 미구현");
    }

    @Override
    public BrokerBalance getBalance() {
        return toBrokerBalance(fetchBalance());
    }

    @Override
    public OrderStatus getOrder(String orderId) {
        // 국내 체결 상세 조회는 현 매매 루프 미사용(주문 응답의 성공여부로 판단). 사유를 담아 UNKNOWN 반환.
        return OrderStatus.failure(orderId, "KIS 국내 주문 상세조회 미구현");
    }

    @Override
    public BigDecimal getCurrentPrice(String symbol) {
        KoreaInvestmentPriceResponse res = fetchCurrentPrice(symbol);
        if (res == null || !res.isSuccess()) {
            return BigDecimal.ZERO;
        }
        return res.getCurrentPrice();
    }

    // ========== broker-중립 매핑 ==========

    /**
     * OrderSpec → 국내 정수 지정가(Limit)로 변환.
     * <ul>
     *   <li>{@code Limit}은 그대로 사용(가격 0 = 시장가 유지).
     *   <li>{@code Notional}(금액 시장가)은 국내 소수주가 불가하므로, 현재가를 조회해
     *       {@code 정수주 = FLOOR(orderAmount / 현재가)}로 환산하고 시장가(가격 0) Limit으로 라우팅한다.
     *       (미국 소수 매수는 TossBrokerAdapter가 Notional을 그대로 처리 — 국내만 이 절삭.)
     * </ul>
     * 산출 수량이 0 이하이거나 현재가를 못 얻으면 null(호출부가 사유를 담아 실패 반환 — 조용실패 금지).
     */
    private OrderSpec.Limit toDomesticLimit(String symbol, OrderSpec spec, String label) {
        if (spec instanceof OrderSpec.Limit l) {
            return l;
        }
        if (spec instanceof OrderSpec.Notional n) {
            BigDecimal price = getCurrentPrice(symbol);
            if (price.signum() <= 0) {
                log.warn("KIS {} 주문: 현재가 조회 실패로 금액기반→정수주 변환 불가 ({})", label, symbol);
                return null;
            }
            BigDecimal qty = n.orderAmount().divide(price, 0, RoundingMode.FLOOR);
            if (qty.signum() <= 0) {
                log.warn("KIS {} 주문: 금액 {} < 1주 {} — 정수주 0 ({})", label, n.orderAmount(), price, symbol);
                return null;
            }
            // 가격 0 = 시장가(executeOrder에서 ORD_DVSN 01로 라우팅). 국내는 시장가 정수주로 체결.
            return new OrderSpec.Limit(qty, BigDecimal.ZERO);
        }
        return null;
    }

    private OrderResult toOrderResult(KoreaInvestmentOrderResponse res) {
        if (res == null) {
            return OrderResult.failure("주문 응답 없음");
        }
        if (res.isSuccess()) {
            return OrderResult.accepted(res.getOrderNo(), res.getMsg1());
        }
        return OrderResult.failure(res.getMsg1());
    }

    private BrokerBalance toBrokerBalance(KoreaInvestmentBalanceResponse res) {
        if (res == null || !res.isSuccess() || res.getOutput2() == null || res.getOutput2().isEmpty()) {
            return BrokerBalance.failure(res != null ? res.getMsg1() : "잔고 응답 없음");
        }
        KoreaInvestmentBalanceResponse.AccountSummary summary = res.getOutput2().get(0);
        BigDecimal availableCash = parseBd(summary.getAvailableCashAmount());
        BigDecimal totalEval = parseBd(summary.getTotalAssetAmount());

        List<BrokerBalance.Holding> holdings = new ArrayList<>();
        if (res.getOutput1() != null) {
            for (KoreaInvestmentBalanceResponse.BalanceItem item : res.getOutput1()) {
                holdings.add(new BrokerBalance.Holding(
                        item.getStockCode(),
                        item.getStockName(),
                        parseBd(item.getHoldingQuantity()),
                        parseBd(item.getAverageBuyPrice()),
                        parseBd(item.getEvaluationAmount())));
            }
        }
        return new BrokerBalance(true, availableCash, totalEval, holdings, null);
    }

    private static BigDecimal parseBd(String v) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(v.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // ========== KIS REST (DTO 내부 전용) ==========

    private KoreaInvestmentOrderResponse executeOrder(String stockCode, String orderType,
                                                      OrderSpec.Limit limit, String trId) {
        ApiConfig.KoreaInvestment config = apiConfig.getKoreaInvestment();
        String url = config.getBaseUrl() + ORDER_PATH;

        int quantity = limit.qty().setScale(0, RoundingMode.FLOOR).intValue();
        BigDecimal price = limit.price();

        // KRW 정수호가용 변환: KIS 국내주문(ORD_UNPR)은 정수 원화 호가만 받는다.
        // 포트 계약(OrderSpec.Limit)은 BigDecimal 무손실이며, 이 절삭은 KIS 어댑터 내부에 격리된다.
        // 토스(미국) 어댑터는 소수 가격을 그대로 전달한다. (P2/P6)
        BigDecimal krwPrice = price.setScale(0, RoundingMode.HALF_UP);
        boolean marketOrder = price.signum() == 0; // 가격 0 = 시장가(기존 동작 유지)

        Map<String, Object> body = new HashMap<>();
        body.put("CANO", config.getAccountMain());
        body.put("ACNT_PRDT_CD", config.getAccountCode());
        body.put("PDNO", stockCode);
        body.put("ORD_DVSN", marketOrder ? "01" : "00");
        body.put("ORD_QTY", String.valueOf(quantity));
        body.put("ORD_UNPR", krwPrice.toPlainString());
        body.put("ALGO_NO", "");

        HttpHeaders headers = createHeaders(trId);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<KoreaInvestmentOrderResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, KoreaInvestmentOrderResponse.class);

            if (response.getBody() != null && "0".equals(response.getBody().getRtCd())) {
                log.info("주문 성공: {} {} {}주 @ {}원",
                        stockCode, orderType.equals("01") ? "매수" : "매도", quantity, krwPrice.toPlainString());
                return response.getBody();
            }

            log.error("주문 실패: {}", response.getBody());
            return response.getBody();
        } catch (Exception e) {
            log.error("주문 API 호출 실패: {}", e.getMessage());
            return KoreaInvestmentOrderResponse.error(e.getMessage());
        }
    }

    private KoreaInvestmentBalanceResponse fetchBalance() {
        ApiConfig.KoreaInvestment config = apiConfig.getKoreaInvestment();
        String cano = config.getAccountMain();
        String acntPrdtCd = config.getAccountCode();
        log.info("잔고 조회 파라미터: CANO={}, ACNT_PRDT_CD={}", cano, acntPrdtCd);

        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl() + BALANCE_PATH)
                .queryParam("CANO", cano)
                .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                .queryParam("AFHR_FLPR_YN", "N")
                .queryParam("OFL_YN", "")
                .queryParam("INQR_DVSN", "02")
                .queryParam("UNPR_DVSN", "01")
                .queryParam("FUND_STTL_ICLD_YN", "N")
                .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                .queryParam("PRCS_DVSN", "00")
                .queryParam("CTX_AREA_FK100", "")
                .queryParam("CTX_AREA_NK100", "")
                .toUriString();

        HttpHeaders headers = createHeaders(balanceTrId());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<KoreaInvestmentBalanceResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, KoreaInvestmentBalanceResponse.class);

            if (response.getBody() != null && "0".equals(response.getBody().getRtCd())) {
                log.info("잔고 조회 성공");
                return response.getBody();
            }

            log.error("잔고 조회 실패: rt_cd={}, msg1={}",
                    response.getBody() != null ? response.getBody().getRtCd() : "null",
                    response.getBody() != null ? response.getBody().getMsg1() : "null");
            return response.getBody();
        } catch (Exception e) {
            log.error("잔고 조회 API 호출 실패: {}", e.getMessage());
            return KoreaInvestmentBalanceResponse.error(e.getMessage());
        }
    }

    private KoreaInvestmentPriceResponse fetchCurrentPrice(String stockCode) {
        ApiConfig.KoreaInvestment config = apiConfig.getKoreaInvestment();

        String url = UriComponentsBuilder.fromHttpUrl(config.getBaseUrl() + PRICE_PATH)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", stockCode)
                .toUriString();

        HttpHeaders headers = createHeaders(PRICE_TR_ID);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<KoreaInvestmentPriceResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, KoreaInvestmentPriceResponse.class);

            if (response.getBody() != null && "0".equals(response.getBody().getRtCd())) {
                return response.getBody();
            }

            log.error("현재가 조회 실패: rt_cd={}, msg_cd={}, msg1={}, stockCode={}",
                    response.getBody() != null ? response.getBody().getRtCd() : "null",
                    response.getBody() != null ? response.getBody().getMsgCd() : "null",
                    response.getBody() != null ? response.getBody().getMsg1() : "null",
                    stockCode);
            return response.getBody();
        } catch (Exception e) {
            log.error("현재가 조회 API 호출 실패: {}", e.getMessage());
            return KoreaInvestmentPriceResponse.error(e.getMessage());
        }
    }

    private HttpHeaders createHeaders(String trId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authorization", "Bearer " + tokenProvider.getAccessToken());
        headers.set("appkey", apiConfig.getKoreaInvestment().getAppKey());
        headers.set("appsecret", apiConfig.getKoreaInvestment().getAppSecret());
        headers.set("tr_id", trId);
        headers.set("custtype", "P");
        headers.set("gt_uid", UUID.randomUUID().toString());
        return headers;
    }
}
