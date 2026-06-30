package com.canagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
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
