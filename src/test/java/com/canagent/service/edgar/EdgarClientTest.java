package com.canagent.service.edgar;

import com.canagent.config.EdgarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("EDGAR 클라이언트 단위테스트 (검증 e: UA 헤더·rate-limit)")
class EdgarClientTest {

    @Mock
    private RestTemplate restTemplate;

    private EdgarProperties props(String ua, int minIntervalMs) {
        EdgarProperties p = new EdgarProperties();
        p.setUserAgent(ua);
        p.setBaseUrl("https://data.sec.gov");
        p.setMinIntervalMs(minIntervalMs);
        return p;
    }

    @Test
    @DisplayName("User-Agent 헤더를 요청마다 부착하고 CIK를 10자리로 패딩한다")
    void sendsUserAgentAndPadsCik() {
        EdgarProperties p = props("MyApp/9 (me@example.com)", 0);
        EdgarClient client = new EdgarClient(restTemplate, p);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"cik\":320193,\"facts\":{\"us-gaap\":{}}}"));

        JsonNode result = client.getCompanyFacts("320193");
        assertThat(result).isNotNull();

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(String.class));

        assertThat(url.getValue())
                .isEqualTo("https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json");
        HttpHeaders headers = entity.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.USER_AGENT)).isEqualTo("MyApp/9 (me@example.com)");
    }

    @Test
    @DisplayName("요청 간 최소 간격(rate-limit)을 강제 대기한다")
    void enforcesRateLimit() {
        int minInterval = 150;
        EdgarProperties p = props("UA", minInterval);
        EdgarClient client = new EdgarClient(restTemplate, p);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"facts\":{\"us-gaap\":{}}}"));

        long start = System.nanoTime();
        client.getCompanyFacts("320193"); // 첫 호출은 대기 없음
        client.getCompanyFacts("789019"); // 두 번째는 minInterval만큼 대기해야 함
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // sleep은 하한만 보장하므로 살짝 여유(마진 20ms)를 두고 하한 검증
        assertThat(elapsedMs).isGreaterThanOrEqualTo(minInterval - 20L);
    }

    @Test
    @DisplayName("4xx(404 등)는 예외를 삼키고 null 반환")
    void swallows4xx() {
        EdgarClient client = new EdgarClient(restTemplate, props("UA", 0));
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThat(client.getCompanyFacts("999999")).isNull();
    }

    @Test
    @DisplayName("숫자 없는 CIK는 호출 없이 null")
    void invalidCik() {
        EdgarClient client = new EdgarClient(restTemplate, props("UA", 0));
        assertThat(client.getCompanyFacts("  ")).isNull();
        assertThat(client.getCompanyFacts(null)).isNull();
    }
}
