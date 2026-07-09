package com.canagent.service;

import com.canagent.config.ApiConfig;
import com.canagent.config.KoreaInvestmentTokenProvider;
import com.canagent.service.dto.KoreaInvestmentOrderResponse;
import com.canagent.service.dto.KoreaInvestmentBalanceResponse;
import com.canagent.service.dto.KoreaInvestmentPriceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class KoreaInvestmentApiClient {

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

    public KoreaInvestmentOrderResponse buy(String stockCode, int quantity, int price) {
        return executeOrder(stockCode, "01", quantity, price, buyTrId());
    }

    public KoreaInvestmentOrderResponse sell(String stockCode, int quantity, int price) {
        return executeOrder(stockCode, "02", quantity, price, sellTrId());
    }

    private KoreaInvestmentOrderResponse executeOrder(String stockCode, String orderType,
                                                      int quantity, int price, String trId) {
        ApiConfig.KoreaInvestment config = apiConfig.getKoreaInvestment();
        String url = config.getBaseUrl() + ORDER_PATH;

        Map<String, Object> body = new HashMap<>();
        body.put("CANO", config.getAccountMain());
        body.put("ACNT_PRDT_CD", config.getAccountCode());
        body.put("PDNO", stockCode);
        body.put("ORD_DVSN", price == 0 ? "01" : "00");
        body.put("ORD_QTY", String.valueOf(quantity));
        body.put("ORD_UNPR", String.valueOf(price));
        body.put("ALGO_NO", "");

        HttpHeaders headers = createHeaders(trId);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<KoreaInvestmentOrderResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, KoreaInvestmentOrderResponse.class);

            if (response.getBody() != null && "0".equals(response.getBody().getRtCd())) {
                log.info("주문 성공: {} {} {}주 @ {}원",
                        stockCode, orderType.equals("01") ? "매수" : "매도", quantity, price);
                return response.getBody();
            }

            log.error("주문 실패: {}", response.getBody());
            return response.getBody();
        } catch (Exception e) {
            log.error("주문 API 호출 실패: {}", e.getMessage());
            return KoreaInvestmentOrderResponse.error(e.getMessage());
        }
    }

    public KoreaInvestmentBalanceResponse getBalance() {
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

    public KoreaInvestmentPriceResponse getCurrentPrice(String stockCode) {
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
