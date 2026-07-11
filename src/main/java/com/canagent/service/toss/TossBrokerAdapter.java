package com.canagent.service.toss;

import com.canagent.config.TossProperties;
import com.canagent.config.TossTokenProvider;
import com.canagent.port.BrokerPort;
import com.canagent.port.dto.BrokerBalance;
import com.canagent.port.dto.OrderResult;
import com.canagent.port.dto.OrderSpec;
import com.canagent.port.dto.OrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 토스증권 브로커 어댑터 — {@link BrokerPort} 미국(소수·USD) 구현.
 *
 * <p>P6(미국 대전환): 토스 주문 라이프사이클(생성/정정/취소/상세)·계좌(assets)·환율을 broker-중립 값타입으로 흡수한다.
 * 인증은 {@link TossTokenProvider}(OAuth Bearer 재사용, 요청 단위 부착 — 공유 RestTemplate 오염 방지, TossCandleClient 선례).
 * P8(broker 전환): {@code toss.broker.enabled=true}일 때만 빈이 생성되며, 이때 {@code @Primary}로 KIS를 제치고
 * 주입되는 BrokerPort가 된다. 프로퍼티가 꺼지면 이 빈은 미생성되어 KIS가 단독 BrokerPort로 폴백한다.
 * us-pivot 기본(application.yml)은 켜짐이나, test 프로필은 KIS 목킹 유지를 위해 꺼둔다(application-test.yml).
 *
 * <p><b>실 샌드박스 호출 불가(승인 대기)</b>: 모든 요청/응답 필드명은 openapi.json 문서 스펙 기준 <b>가정값</b>이며
 * 명명 상수로 이 파일 한 곳에 집중했다. 실호출 확정 시 상수만 교체한다. 미확정은 {@code === PoC 미확정 ===} 주석 표시.
 * 필드명 오타가 조용한 0건으로 새지 않도록, 실패·부재 시 사유를 담은 실패 결과를 반환하고 테스트가 매핑을 검증한다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "toss.broker.enabled", havingValue = "true")
public class TossBrokerAdapter implements BrokerPort {

    private static final Logger log = LoggerFactory.getLogger(TossBrokerAdapter.class);

    // === openapi.json 스펙 기준(B실사 §1): 주문 라이프사이클 엔드포인트. KR/US 통합. ===
    private static final String ORDERS_PATH = "/api/v1/orders";
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    private static final String ASSETS_PATH = "/api/v1/assets";
    private static final String EXCHANGE_RATE_PATH = "/api/v1/exchange-rate";

    // === openapi.json 스펙 기준: 주문 요청 필드 ===
    private static final String REQ_SYMBOL = "symbol";
    private static final String REQ_SIDE = "side";           // BUY / SELL
    private static final String REQ_ORDER_TYPE = "orderType"; // MARKET / LIMIT
    private static final String REQ_ORDER_AMOUNT = "orderAmount"; // 소수 매수(금액기반)
    private static final String REQ_QUANTITY = "quantity";        // 정수·지정가
    private static final String REQ_PRICE = "price";              // 지정가

    private static final String SIDE_BUY = "BUY";
    private static final String SIDE_SELL = "SELL";
    private static final String ORDER_TYPE_MARKET = "MARKET";
    private static final String ORDER_TYPE_LIMIT = "LIMIT";

    // === openapi.json 스펙 기준: 주문 응답/상세 필드 ===
    private static final String FIELD_ORDER_ID = "orderId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_FILLED_QTY = "filledQuantity";
    private static final String FIELD_AVG_FILL_PRICE = "averageFillPrice";
    private static final String FIELD_COMMISSION = "commission";
    private static final String FIELD_TAX = "tax";

    // === openapi.json 스펙 기준: assets(잔고) 응답 필드 ===
    // === PoC 미확정 ===: USD 현금 필드명(availableCash 가정 — 실호출 확정 시 교체). B실사 §3.
    private static final String FIELD_AVAILABLE_CASH = "availableCash";
    private static final String FIELD_TOTAL_EVAL = "totalEvaluationAmount";
    private static final String FIELD_HOLDINGS = "holdings";
    private static final String FIELD_H_SYMBOL = "symbol";
    private static final String FIELD_H_NAME = "name";
    private static final String FIELD_H_QTY = "quantity";
    private static final String FIELD_H_AVG_PRICE = "averageBuyPrice";
    private static final String FIELD_H_EVAL = "evaluationAmount";

    // === openapi.json 스펙 기준: 현재가/환율 필드 ===
    private static final String FIELD_CURRENT_PRICE = "currentPrice"; // === PoC 미확정 === (assets/price 응답 자릿수)
    private static final String FIELD_EXCHANGE_RATE = "rate";         // USD/KRW

    // === PoC 미확정 ===: 계좌 지정 헤더명. B실사 2차출처 힌트(X-Tossinvest-Account) — 실호출 확정 시 교체/제거.
    private static final String HEADER_ACCOUNT = "X-Tossinvest-Account";

    private final RestTemplate restTemplate;
    private final TossProperties props;
    private final TossTokenProvider tokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TossBrokerAdapter(RestTemplate restTemplate, TossProperties props, TossTokenProvider tokenProvider) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.tokenProvider = tokenProvider;
    }

    // ========== BrokerPort ==========

    @Override
    public OrderResult placeBuy(String symbol, OrderSpec spec) {
        return placeOrder(symbol, SIDE_BUY, spec);
    }

    @Override
    public OrderResult placeSell(String symbol, OrderSpec spec) {
        return placeOrder(symbol, SIDE_SELL, spec);
    }

    private OrderResult placeOrder(String symbol, String side, OrderSpec spec) {
        if (symbol == null || symbol.isBlank()) {
            return OrderResult.failure("심볼 없음");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(REQ_SYMBOL, symbol);
        body.put(REQ_SIDE, side);
        // 소수 매수 = 금액기반 시장가 자동 라우팅(orderAmount). 정수·지정가는 LIMIT(qty,price).
        if (spec instanceof OrderSpec.Notional n) {
            body.put(REQ_ORDER_TYPE, ORDER_TYPE_MARKET);
            body.put(REQ_ORDER_AMOUNT, n.orderAmount().toPlainString());
        } else if (spec instanceof OrderSpec.Limit l) {
            body.put(REQ_ORDER_TYPE, ORDER_TYPE_LIMIT);
            body.put(REQ_QUANTITY, l.qty().toPlainString());
            body.put(REQ_PRICE, l.price().toPlainString());
        } else {
            return OrderResult.failure("알 수 없는 주문 명세");
        }
        return postOrder(props.getBaseUrl() + ORDERS_PATH, body, side + " 주문 " + symbol);
    }

    @Override
    public OrderResult modify(String orderId, OrderSpec spec) {
        if (orderId == null || orderId.isBlank()) {
            return OrderResult.failure("주문번호 없음");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // 정정은 미체결 수량·가격 변경 — 정수 지정가만 의미(B실사 §1).
        if (spec instanceof OrderSpec.Limit l) {
            body.put(REQ_QUANTITY, l.qty().toPlainString());
            body.put(REQ_PRICE, l.price().toPlainString());
        } else {
            return OrderResult.failure("정정은 지정가(Limit)만 가능");
        }
        return postOrder(props.getBaseUrl() + ORDERS_PATH + "/" + orderId + "/modify", body, "정정 " + orderId);
    }

    @Override
    public OrderResult cancel(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return OrderResult.failure("주문번호 없음");
        }
        return postOrder(props.getBaseUrl() + ORDERS_PATH + "/" + orderId + "/cancel",
                new LinkedHashMap<>(), "취소 " + orderId);
    }

    @Override
    public OrderStatus getOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return OrderStatus.failure(orderId, "주문번호 없음");
        }
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return OrderStatus.failure(orderId, "토큰 없음");
        }
        String url = props.getBaseUrl() + ORDERS_PATH + "/" + orderId;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, getEntity(token), String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return OrderStatus.failure(orderId, "빈 응답");
            }
            return mapOrderStatus(objectMapper.readTree(bodyStr), orderId);
        } catch (HttpClientErrorException e) {
            handle4xx(e, "주문상세 " + orderId);
            return OrderStatus.failure(orderId, "4xx " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            log.warn("토스 주문상세 5xx {} {}", orderId, e.getStatusCode());
            return OrderStatus.failure(orderId, "5xx " + e.getStatusCode());
        } catch (Exception e) {
            log.warn("토스 주문상세 실패 {}: {}", orderId, e.getMessage());
            return OrderStatus.failure(orderId, e.getMessage());
        }
    }

    @Override
    public BrokerBalance getBalance() {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return BrokerBalance.failure("토큰 없음");
        }
        String url = props.getBaseUrl() + ASSETS_PATH;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, getEntity(token), String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return BrokerBalance.failure("빈 응답");
            }
            return mapBalance(objectMapper.readTree(bodyStr));
        } catch (HttpClientErrorException e) {
            handle4xx(e, "assets");
            return BrokerBalance.failure("4xx " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            log.warn("토스 assets 5xx {}", e.getStatusCode());
            return BrokerBalance.failure("5xx " + e.getStatusCode());
        } catch (Exception e) {
            log.warn("토스 assets 실패: {}", e.getMessage());
            return BrokerBalance.failure(e.getMessage());
        }
    }

    @Override
    public BigDecimal getCurrentPrice(String symbol) {
        // 현재가는 시세 경로(TossCandleClient/MarketDataPort)와 별개로, 주문 판단용 스팟가가 필요할 때 사용.
        // === PoC 미확정 ===: 스팟 현재가 엔드포인트(assets/price 스키마). 최신 캔들 종가 대체가 1차 경로이므로
        // 브로커 스팟가는 미확정으로 두고, 미구현 시 0을 반환한다(조용실패가 아니라 명시적 0 — 호출부가 skip).
        log.debug("토스 브로커 스팟 현재가 미구현(시세는 MarketDataPort 사용): {}", symbol);
        return BigDecimal.ZERO;
    }

    // ========== 매핑 (필드명 상수 → 값타입, 유일 지점) ==========

    private OrderResult postOrder(String url, Map<String, Object> body, String label) {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return OrderResult.failure("토큰 없음");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            headers.set(HEADER_ACCOUNT, props.getAccount());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return OrderResult.failure(label + ": 빈 응답");
            }
            return mapOrderResult(objectMapper.readTree(bodyStr), label);
        } catch (HttpClientErrorException e) {
            handle4xx(e, label);
            return OrderResult.failure(label + " 4xx " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            log.warn("토스 {} 5xx {}", label, e.getStatusCode());
            return OrderResult.failure(label + " 5xx " + e.getStatusCode());
        } catch (Exception e) {
            log.warn("토스 {} 실패: {}", label, e.getMessage());
            return OrderResult.failure(label + " 실패: " + e.getMessage());
        }
    }

    /** 주문 응답 → OrderResult. orderId가 있으면 접수 성공으로 본다(견고). */
    private OrderResult mapOrderResult(JsonNode root, String label) {
        String orderId = text(root, FIELD_ORDER_ID);
        String message = text(root, FIELD_MESSAGE);
        if (orderId != null && !orderId.isBlank()) {
            return new OrderResult(true, orderId, message,
                    decimalOrZero(root, FIELD_FILLED_QTY), decimalOrNull(root, FIELD_AVG_FILL_PRICE));
        }
        return OrderResult.failure(message != null ? message : label + ": orderId 없음");
    }

    private OrderStatus mapOrderStatus(JsonNode root, String orderId) {
        OrderStatus.Status status = parseStatus(text(root, FIELD_STATUS));
        return new OrderStatus(
                orderId,
                status,
                decimalOrZero(root, FIELD_FILLED_QTY),
                decimalOrNull(root, FIELD_AVG_FILL_PRICE),
                decimalOrNull(root, FIELD_COMMISSION),
                decimalOrNull(root, FIELD_TAX),
                text(root, FIELD_MESSAGE));
    }

    private BrokerBalance mapBalance(JsonNode root) {
        BigDecimal availableCash = decimalOrZero(root, FIELD_AVAILABLE_CASH);
        BigDecimal totalEval = decimalOrZero(root, FIELD_TOTAL_EVAL);
        List<BrokerBalance.Holding> holdings = new ArrayList<>();
        JsonNode arr = root.path(FIELD_HOLDINGS);
        if (arr.isArray()) {
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                JsonNode h = it.next();
                holdings.add(new BrokerBalance.Holding(
                        text(h, FIELD_H_SYMBOL),
                        text(h, FIELD_H_NAME),
                        decimalOrZero(h, FIELD_H_QTY),
                        decimalOrZero(h, FIELD_H_AVG_PRICE),
                        decimalOrZero(h, FIELD_H_EVAL)));
            }
        }
        return new BrokerBalance(true, availableCash, totalEval, holdings, null);
    }

    /**
     * USD/KRW 환율 조회. 환전 방식(자동 vs 사전환전) 미확정(E §8-3)이라 사이징은 availableCash(USD) 기준으로 두고,
     * 이 값은 표시·환산 보조용이다. 실패 시 null.
     */
    public BigDecimal getExchangeRate() {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return null;
        }
        String url = props.getBaseUrl() + EXCHANGE_RATE_PATH;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, getEntity(token), String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return null;
            }
            return decimalOrNull(objectMapper.readTree(bodyStr), FIELD_EXCHANGE_RATE);
        } catch (HttpClientErrorException e) {
            handle4xx(e, "환율");
            return null;
        } catch (Exception e) {
            log.warn("토스 환율 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private static OrderStatus.Status parseStatus(String s) {
        if (s == null || s.isBlank()) return OrderStatus.Status.UNKNOWN;
        try {
            return OrderStatus.Status.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OrderStatus.Status.UNKNOWN;
        }
    }

    // ========== HTTP·파싱 헬퍼 (TossCandleClient 에러핸들링 선례) ==========

    private HttpEntity<Void> getEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HEADER_ACCOUNT, props.getAccount());
        return new HttpEntity<>(headers);
    }

    /** 401은 토큰 무효화(다음 호출 재발급), 429는 rate-limit 경고. TossCandleClient와 동일. */
    private void handle4xx(HttpClientErrorException e, String label) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            log.warn("토스 {} 401 — 토큰 무효화 후 다음 호출 재발급", label);
            tokenProvider.invalidateToken();
        } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            log.warn("토스 {} 429 rate-limit", label);
        } else {
            log.warn("토스 {} 4xx {}: {}", label, e.getStatusCode(), e.getStatusText());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOrZero(JsonNode node, String field) {
        BigDecimal v = decimalOrNull(node, field);
        return v != null ? v : BigDecimal.ZERO;
    }
}
