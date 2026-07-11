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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 토스증권 브로커 어댑터 — {@link BrokerPort} 미국(소수·USD) 구현.
 *
 * <p>P6(미국 대전환): 토스 주문 라이프사이클(생성/정정/취소/상세)·잔고(buying-power+holdings)·현재가·환율을
 * broker-중립 값타입으로 흡수한다. 인증은 {@link TossTokenProvider}(OAuth Bearer 재사용, 요청 단위 부착 —
 * 공유 RestTemplate 오염 방지). P8(broker 전환): {@code toss.broker.enabled=true}일 때만 빈이 생성되며 @Primary로
 * KIS를 제친다. 프로퍼티가 꺼지면 KIS가 단독 BrokerPort로 폴백한다(test 프로필은 KIS 목킹 유지 위해 꺼둠).
 *
 * <p><b>=== PoC 실확정(2026-07-11) ===</b>: 실 라이브 키로 호출해 확정한 스펙(00_poc_confirmed_spec.md)을 반영했다.
 * <ul>
 *   <li>모든 성공 응답은 최상위 {@code {"result":...}} 래핑 → 매핑 전 {@link #unwrap(JsonNode)}로 벗긴다.
 *   <li>잔고: {@code /buying-power?currency=USD}(현금) + {@code /holdings}(보유·평가, 금액은 {krw,usd} 중첩).
 *   <li>계좌콜 헤더 {@code X-Tossinvest-Account: <accountSeq>}. accountSeq는 {@code /accounts} result[0]에서 조회·캐시(env override).
 *   <li>현재가 {@code /prices?symbols=} result[0].lastPrice, 환율 {@code ?baseCurrency=USD&quoteCurrency=KRW}.
 *   <li>주문: 소수매수=orderAmount+MARKET, 지정가=quantity+LIMIT+price, 소수매도=quantity+MARKET(price 금지). clientOrderId 멱등성.
 * </ul>
 * 필드명 오타가 조용한 0건으로 새지 않도록, 실패·부재 시 사유를 담은 실패 결과를 반환하고 테스트가 매핑을 검증한다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "toss.broker.enabled", havingValue = "true")
public class TossBrokerAdapter implements BrokerPort {

    private static final Logger log = LoggerFactory.getLogger(TossBrokerAdapter.class);

    // === PoC 확정(2026-07-11): 엔드포인트 경로 ===
    private static final String ORDERS_PATH = "/api/v1/orders";
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    private static final String BUYING_POWER_PATH = "/api/v1/buying-power";  // /assets(404) 대체
    private static final String HOLDINGS_PATH = "/api/v1/holdings";
    private static final String PRICES_PATH = "/api/v1/prices";
    private static final String EXCHANGE_RATE_PATH = "/api/v1/exchange-rate";

    // === PoC 확정(2026-07-11): 응답 최상위 래핑 키 ===
    private static final String FIELD_RESULT = "result";

    // === PoC 확정(2026-07-11, §8): 주문 요청 필드 ===
    private static final String REQ_SYMBOL = "symbol";
    private static final String REQ_SIDE = "side";             // BUY / SELL
    private static final String REQ_ORDER_TYPE = "orderType";  // MARKET / LIMIT
    private static final String REQ_ORDER_AMOUNT = "orderAmount"; // 소수 매수(금액기반, US MARKET 전용)
    private static final String REQ_QUANTITY = "quantity";       // 정수·지정가·소수매도
    private static final String REQ_PRICE = "price";            // LIMIT 필수 / MARKET 전달 금지
    private static final String REQ_CLIENT_ORDER_ID = "clientOrderId"; // 멱등성 키(10분)

    private static final String SIDE_BUY = "BUY";
    private static final String SIDE_SELL = "SELL";
    private static final String ORDER_TYPE_MARKET = "MARKET";
    private static final String ORDER_TYPE_LIMIT = "LIMIT";

    // === PoC 확정(2026-07-11, §8·§9): 주문 응답/상세 필드 ===
    private static final String FIELD_ORDER_ID = "orderId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_MESSAGE = "message";
    // 체결정보는 execution 중첩 객체(§9).
    private static final String FIELD_EXECUTION = "execution";
    private static final String FIELD_FILLED_QTY = "filledQuantity";
    private static final String FIELD_AVG_FILL_PRICE = "averageFilledPrice"; // (구)averageFillPrice → 실제 averageFilledPrice
    private static final String FIELD_COMMISSION = "commission";
    private static final String FIELD_TAX = "tax";

    // === PoC 확정(2026-07-11, §3): 매수가능현금 응답 필드 ===
    private static final String FIELD_CASH_BUYING_POWER = "cashBuyingPower"; // (구)availableCash → /buying-power cashBuyingPower

    // === PoC 확정(2026-07-11, §4): holdings 응답 필드(금액은 {krw,usd} 중첩) ===
    private static final String FIELD_MARKET_VALUE = "marketValue";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_USD = "usd";
    private static final String FIELD_ITEMS = "items";
    private static final String FIELD_H_SYMBOL = "symbol";
    private static final String FIELD_H_NAME = "name";
    private static final String FIELD_H_QTY = "quantity";
    private static final String FIELD_H_AVG_PRICE = "averagePurchasePrice";

    // === PoC 확정(2026-07-11, §5): 현재가 응답 필드 ===
    private static final String FIELD_LAST_PRICE = "lastPrice";
    private static final String PARAM_SYMBOLS = "symbols";

    // === PoC 확정(2026-07-11, §6): 환율 요청 파라미터·응답 필드 ===
    private static final String PARAM_BASE_CURRENCY = "baseCurrency";
    private static final String PARAM_QUOTE_CURRENCY = "quoteCurrency";
    private static final String FIELD_EXCHANGE_RATE = "rate";
    private static final String CURRENCY_USD = "USD";
    private static final String CURRENCY_KRW = "KRW";
    private static final String PARAM_CURRENCY = "currency";

    // === PoC 확정(2026-07-11, §2): 계좌 헤더·accounts 응답 필드 ===
    private static final String HEADER_ACCOUNT = "X-Tossinvest-Account";
    private static final String FIELD_ACCOUNT_SEQ = "accountSeq";

    private final RestTemplate restTemplate;
    private final TossProperties props;
    private final TossTokenProvider tokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** accountSeq 캐시(계좌콜 헤더). env override 우선, 없으면 /accounts에서 1회 조회 후 캐시. */
    private volatile String cachedAccountSeq;

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

    /**
     * 주문 본문 조립.
     * === PoC 확정(2026-07-11, §8) ===:
     * <ul>
     *   <li>소수 매수(Notional) = {@code orderAmount}+MARKET(금액기반, US 정규장). price 없음.
     *   <li>지정가(Limit) = {@code quantity}+LIMIT+{@code price}(US 정밀 반올림). MARKET엔 price 금지.
     *   <li><b>소수 매도(Limit·SELL)</b> = {@code quantity}(소수)+MARKET(price 없음). US 소수주는 MARKET만 매도 가능.
     *       정수 매도는 LIMIT 유지. 소수 판단은 quantity의 스케일로 한다.
     * </ul>
     * clientOrderId(멱등성 키)를 매 주문 고유값으로 부착한다.
     */
    private OrderResult placeOrder(String symbol, String side, OrderSpec spec) {
        if (symbol == null || symbol.isBlank()) {
            return OrderResult.failure("심볼 없음");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(REQ_SYMBOL, symbol);
        body.put(REQ_SIDE, side);
        if (spec instanceof OrderSpec.Notional n) {
            // 소수 매수: 금액기반 시장가(orderAmount). price 없음.
            body.put(REQ_ORDER_TYPE, ORDER_TYPE_MARKET);
            body.put(REQ_ORDER_AMOUNT, n.orderAmount().toPlainString());
        } else if (spec instanceof OrderSpec.Limit l) {
            if (SIDE_SELL.equals(side) && isFractional(l.qty())) {
                // === PoC 확정(2026-07-11, §8): US 소수주 매도는 MARKET+quantity만 가능(price 금지). ===
                body.put(REQ_ORDER_TYPE, ORDER_TYPE_MARKET);
                body.put(REQ_QUANTITY, l.qty().toPlainString());
                log.debug("토스 소수 매도 → MARKET (price 무시): {} qty={}", symbol, l.qty().toPlainString());
            } else {
                // 지정가: LIMIT+quantity+price(US 정밀 반올림).
                body.put(REQ_ORDER_TYPE, ORDER_TYPE_LIMIT);
                body.put(REQ_QUANTITY, l.qty().toPlainString());
                body.put(REQ_PRICE, roundUsPrice(l.price()).toPlainString());
            }
        } else {
            return OrderResult.failure("알 수 없는 주문 명세");
        }
        body.put(REQ_CLIENT_ORDER_ID, newClientOrderId());
        return postOrder(props.getBaseUrl() + ORDERS_PATH, body, side + " 주문 " + symbol);
    }

    @Override
    public OrderResult modify(String orderId, OrderSpec spec) {
        if (orderId == null || orderId.isBlank()) {
            return OrderResult.failure("주문번호 없음");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // 정정은 미체결 수량·가격 변경 — 정수 지정가만 의미(§8).
        if (spec instanceof OrderSpec.Limit l) {
            body.put(REQ_QUANTITY, l.qty().toPlainString());
            body.put(REQ_PRICE, roundUsPrice(l.price()).toPlainString());
        } else {
            return OrderResult.failure("정정은 지정가(Limit)만 가능");
        }
        body.put(REQ_CLIENT_ORDER_ID, newClientOrderId());
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
            return mapOrderStatus(unwrap(objectMapper.readTree(bodyStr)), orderId);
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

    /**
     * 계좌 잔고. === PoC 확정(2026-07-11, §3·§4) ===: 두 호출을 합성한다 —
     * {@code /buying-power?currency=USD}(현금) + {@code /holdings}(총평가·보유목록, 금액 {krw,usd} 중첩).
     * BrokerBalance record shape은 유지(대시보드 무영향), 소스만 교체.
     */
    @Override
    public BrokerBalance getBalance() {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return BrokerBalance.failure("토큰 없음");
        }
        BigDecimal cash;
        try {
            cash = fetchCashBuyingPower(token);
        } catch (HttpClientErrorException e) {
            handle4xx(e, "buying-power");
            return BrokerBalance.failure("4xx " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            log.warn("토스 buying-power 5xx {}", e.getStatusCode());
            return BrokerBalance.failure("5xx " + e.getStatusCode());
        } catch (Exception e) {
            log.warn("토스 buying-power 실패: {}", e.getMessage());
            return BrokerBalance.failure(e.getMessage());
        }

        BigDecimal totalEval = BigDecimal.ZERO;
        List<BrokerBalance.Holding> holdings = new ArrayList<>();
        try {
            JsonNode h = fetchHoldings(token);
            totalEval = usdAmount(h.path(FIELD_MARKET_VALUE));
            JsonNode items = h.path(FIELD_ITEMS);
            if (items.isArray()) {
                for (Iterator<JsonNode> it = items.elements(); it.hasNext(); ) {
                    JsonNode item = it.next();
                    holdings.add(new BrokerBalance.Holding(
                            text(item, FIELD_H_SYMBOL),
                            text(item, FIELD_H_NAME),
                            decimalOrZero(item, FIELD_H_QTY),
                            decimalOrZero(item, FIELD_H_AVG_PRICE),
                            usdAmount(item.path(FIELD_MARKET_VALUE))));
                }
            }
        } catch (HttpClientErrorException e) {
            handle4xx(e, "holdings");
            // 현금은 조회됐으나 보유 조회 실패 — 사유를 담아 부분 성공 반환(조용실패 방지).
            return new BrokerBalance(true, cash, BigDecimal.ZERO, List.of(), "holdings 4xx " + e.getStatusCode());
        } catch (Exception e) {
            log.warn("토스 holdings 실패: {}", e.getMessage());
            return new BrokerBalance(true, cash, BigDecimal.ZERO, List.of(), "holdings 실패: " + e.getMessage());
        }
        return new BrokerBalance(true, cash, totalEval, holdings, null);
    }

    @Override
    public BigDecimal getCurrentPrice(String symbol) {
        // === PoC 확정(2026-07-11, §5): /prices?symbols=<sym> → result[0].lastPrice. ===
        if (symbol == null || symbol.isBlank()) {
            return BigDecimal.ZERO;
        }
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.warn("토스 현재가: 토큰 없음 — {} 0 반환", symbol);
            return BigDecimal.ZERO;
        }
        String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + PRICES_PATH)
                .queryParam(PARAM_SYMBOLS, symbol)
                .toUriString();
        try {
            // 현재가는 계좌 무관 시세 — 계좌헤더/accounts 조회 없이 Bearer만.
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(token)), String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return BigDecimal.ZERO;
            }
            JsonNode result = objectMapper.readTree(bodyStr).path(FIELD_RESULT);
            if (result.isArray() && result.size() > 0) {
                BigDecimal price = decimalOrNull(result.get(0), FIELD_LAST_PRICE);
                return price != null ? price : BigDecimal.ZERO;
            }
            log.warn("토스 현재가: {} result 비어있음", symbol);
            return BigDecimal.ZERO;
        } catch (HttpClientErrorException e) {
            handle4xx(e, "현재가 " + symbol);
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("토스 현재가 조회 실패 {}: {}", symbol, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // ========== 잔고 세부 호출 ==========

    private BigDecimal fetchCashBuyingPower(String token) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + BUYING_POWER_PATH)
                .queryParam(PARAM_CURRENCY, CURRENCY_USD)  // currency 필수(§3)
                .toUriString();
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, getEntity(token), String.class);
        String bodyStr = resp.getBody();
        if (bodyStr == null || bodyStr.isBlank()) {
            return BigDecimal.ZERO;
        }
        return decimalOrZero(unwrap(objectMapper.readTree(bodyStr)), FIELD_CASH_BUYING_POWER);
    }

    private JsonNode fetchHoldings(String token) throws Exception {
        String url = props.getBaseUrl() + HOLDINGS_PATH;
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, getEntity(token), String.class);
        String bodyStr = resp.getBody();
        if (bodyStr == null || bodyStr.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return unwrap(objectMapper.readTree(bodyStr));
    }

    /** {@code {amount:{krw,usd},...}} 중첩에서 USD 금액을 꺼낸다(§4). 부재 시 0. */
    private static BigDecimal usdAmount(JsonNode moneyNode) {
        if (moneyNode == null || moneyNode.isMissingNode()) return BigDecimal.ZERO;
        return decimalOrZero(moneyNode.path(FIELD_AMOUNT), FIELD_USD);
    }

    // ========== 주문 POST·매핑 ==========

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
            headers.set(HEADER_ACCOUNT, resolveAccountSeq(token));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return OrderResult.failure(label + ": 빈 응답");
            }
            return mapOrderResult(unwrap(objectMapper.readTree(bodyStr)), label);
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

    /** 주문 응답 → OrderResult. result.orderId가 있으면 접수 성공으로 본다(§8, 견고). */
    private OrderResult mapOrderResult(JsonNode result, String label) {
        String orderId = text(result, FIELD_ORDER_ID);
        String message = text(result, FIELD_MESSAGE);
        if (orderId != null && !orderId.isBlank()) {
            return OrderResult.accepted(orderId, message);
        }
        return OrderResult.failure(message != null ? message : label + ": orderId 없음");
    }

    /** 주문상세 → OrderStatus. 체결정보는 execution 중첩(§9). status enum은 실제 값집합. */
    private OrderStatus mapOrderStatus(JsonNode result, String orderId) {
        OrderStatus.Status status = parseStatus(text(result, FIELD_STATUS));
        JsonNode execution = result.path(FIELD_EXECUTION);
        return new OrderStatus(
                orderId,
                status,
                decimalOrZero(execution, FIELD_FILLED_QTY),
                decimalOrNull(execution, FIELD_AVG_FILL_PRICE),
                decimalOrNull(execution, FIELD_COMMISSION),
                decimalOrNull(execution, FIELD_TAX),
                text(result, FIELD_MESSAGE));
    }

    /**
     * USD/KRW 환율 조회. === PoC 확정(2026-07-11, §6) ===: baseCurrency=USD&quoteCurrency=KRW 필수, result.rate 언랩.
     * 표시·환산 보조용. 실패 시 null.
     */
    public BigDecimal getExchangeRate() {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            return null;
        }
        String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + EXCHANGE_RATE_PATH)
                .queryParam(PARAM_BASE_CURRENCY, CURRENCY_USD)
                .queryParam(PARAM_QUOTE_CURRENCY, CURRENCY_KRW)
                .toUriString();
        try {
            // 환율도 계좌 무관 — Bearer만.
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(token)), String.class);
            String bodyStr = resp.getBody();
            if (bodyStr == null || bodyStr.isBlank()) {
                return null;
            }
            return decimalOrNull(unwrap(objectMapper.readTree(bodyStr)), FIELD_EXCHANGE_RATE);
        } catch (HttpClientErrorException e) {
            handle4xx(e, "환율");
            return null;
        } catch (Exception e) {
            log.warn("토스 환율 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    // ========== 계좌·주문 헬퍼 ==========

    /**
     * 계좌콜 헤더용 accountSeq 확정. === PoC 확정(2026-07-11, §2) ===:
     * TOSS_ACCOUNT env override 우선, 없으면 {@code /accounts} result[0].accountSeq를 조회·캐시한다.
     * 조회 실패 시 null(호출부 헤더 미부착 → 서버가 400/403으로 사유 반환, 조용실패 아님).
     */
    private String resolveAccountSeq(String token) {
        String override = props.getAccount();
        if (override != null && !override.isBlank()) {
            return override;
        }
        String cached = cachedAccountSeq;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedAccountSeq != null) {
                return cachedAccountSeq;
            }
            try {
                String url = props.getBaseUrl() + ACCOUNTS_PATH;
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(token)), String.class);
                String bodyStr = resp.getBody();
                if (bodyStr != null && !bodyStr.isBlank()) {
                    JsonNode result = objectMapper.readTree(bodyStr).path(FIELD_RESULT);
                    if (result.isArray() && result.size() > 0) {
                        String seq = text(result.get(0), FIELD_ACCOUNT_SEQ);
                        if (seq != null) {
                            cachedAccountSeq = seq;
                            log.info("토스 accountSeq 조회·캐시: {}", seq);
                            return seq;
                        }
                    }
                }
                log.warn("토스 accounts: accountSeq 없음");
            } catch (Exception e) {
                log.warn("토스 accounts 조회 실패: {}", e.getMessage());
            }
            return null;
        }
    }

    /** 멱등성 키(§8, 10분). 주문마다 고유. */
    private static String newClientOrderId() {
        return UUID.randomUUID().toString();
    }

    /** US 가격 정밀(§8): <$1 4자리, ≥$1 2자리 반올림. */
    static BigDecimal roundUsPrice(BigDecimal price) {
        if (price == null) return null;
        int scale = price.abs().compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
        return price.setScale(scale, RoundingMode.HALF_UP);
    }

    /** 소수 수량 여부(정수가 아니면 true). US 소수주 매도 MARKET 판단용. */
    private static boolean isFractional(BigDecimal qty) {
        if (qty == null) return false;
        return qty.stripTrailingZeros().scale() > 0;
    }

    private static OrderStatus.Status parseStatus(String s) {
        if (s == null || s.isBlank()) return OrderStatus.Status.UNKNOWN;
        try {
            return OrderStatus.Status.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OrderStatus.Status.UNKNOWN;
        }
    }

    // ========== HTTP·파싱 헬퍼 ==========

    /** === PoC 확정(2026-07-11): 성공 응답 최상위 {@code result} 래핑을 벗긴다. 부재 시 루트 폴백. === */
    private JsonNode unwrap(JsonNode root) {
        return root.has(FIELD_RESULT) ? root.path(FIELD_RESULT) : root;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    /** 계좌콜 GET 엔티티(Bearer + 계좌헤더). */
    private HttpEntity<Void> getEntity(String token) {
        HttpHeaders headers = bearerHeaders(token);
        headers.set(HEADER_ACCOUNT, resolveAccountSeq(token));
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
