package com.canagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// test 프로필에선 로컬 .env를 System property로 주입하지 않는다.
// (JVM 전역 오염 → ApiConfig.env()가 실계좌/실서버 값을 읽어 테스트 격리 깨짐 방지)
@Component
@Profile("!test")
public class DotenvConfig {

    private static final Logger log = LoggerFactory.getLogger(DotenvConfig.class);

    @PostConstruct
    public void load() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(System.getProperty("user.dir"))
                    .ignoreIfMissing()
                    .load();

            dotenv.entries().forEach(entry -> {
                if (System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });
            log.info(".env 파일 로드 완료 ({} 개 변수)", dotenv.entries().size());
        } catch (Exception e) {
            log.warn(".env 파일 로드 실패: {}", e.getMessage());
        }
    }
}
