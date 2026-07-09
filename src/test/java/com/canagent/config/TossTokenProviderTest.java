package com.canagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("토스 토큰 제공자 (검증 d: 캐시·만료갱신·invalidate)")
class TossTokenProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private TossProperties props() {
        TossProperties p = new TossProperties();
        p.setAppKey("KEY");
        p.setAppSecret("SECRET");
        p.setBaseUrl("https://toss.test");
        return p;
    }

    @Test
    @DisplayName("첫 발급 후 만료 전에는 캐시된 토큰을 재사용(HTTP 1회)")
    void cachesToken() {
        TossTokenProvider provider = new TossTokenProvider(restTemplate, props());
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "TOK-1", "expires_in", 86400));

        String a = provider.getAccessToken();
        String b = provider.getAccessToken();

        assertThat(a).isEqualTo("TOK-1");
        assertThat(b).isEqualTo("TOK-1");
        verify(restTemplate, times(1)).postForObject(any(String.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("만료(버퍼 내 expires_in)면 다음 조회에서 재발급(HTTP 2회)")
    void refreshesOnExpiry() {
        TossTokenProvider provider = new TossTokenProvider(restTemplate, props());
        // expires_in=0 → 만료시각 = now → 즉시 만료 → 다음 조회 시 재발급
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "TOK-A", "expires_in", 0))
                .thenReturn(Map.of("access_token", "TOK-B", "expires_in", 0));

        String first = provider.getAccessToken();
        String second = provider.getAccessToken();

        assertThat(first).isEqualTo("TOK-A");
        assertThat(second).isEqualTo("TOK-B");
        verify(restTemplate, times(2)).postForObject(any(String.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("invalidate 후에는 강제 재발급")
    void invalidateForcesRefresh() {
        TossTokenProvider provider = new TossTokenProvider(restTemplate, props());
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "TOK-1", "expires_in", 86400))
                .thenReturn(Map.of("access_token", "TOK-2", "expires_in", 86400));

        assertThat(provider.getAccessToken()).isEqualTo("TOK-1");
        provider.invalidateToken();
        assertThat(provider.getAccessToken()).isEqualTo("TOK-2");
        verify(restTemplate, times(2)).postForObject(any(String.class), any(), eq(Map.class));
    }

    @Test
    @DisplayName("access_token 없는 응답이면 null(무예외)")
    void nullOnMissingToken() {
        TossTokenProvider provider = new TossTokenProvider(restTemplate, props());
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("error", "invalid_client"));

        assertThat(provider.getAccessToken()).isNull();
    }

    @Test
    @DisplayName("토큰 URL은 base-url + token-path로 조합(override 없을 때)")
    void tokenUrlComposition() {
        TossProperties p = props();
        assertThat(p.getTokenUrl()).isEqualTo("https://toss.test/oauth2/token");
    }
}
