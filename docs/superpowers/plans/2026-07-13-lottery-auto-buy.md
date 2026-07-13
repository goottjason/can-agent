# 복권 자동구매 부가기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 동행복권에서 로또6/45·연금복권720+를 매주 자동 1게임/1조 구매하고, 추첨 직후 당첨 결과와 예치금 부족(<3,000원)을 텔레그램으로 알린다.

**Architecture:** 구매(로그인·주문 등 인증·취약 영역)는 검증된 파이썬 사이드카(dhapi 로또 + Playwright 연금)에 위임하고, Java(Spring Boot)가 `LotterySidecarPort`를 통해 subprocess로 호출한다. Java는 스케줄링(`@Scheduled`, `Asia/Seoul`)·DB 저장·당첨확인(공개 API)·예치금 알림·텔레그램 발송을 담당한다. 크리덴셜은 사이드카 `.env`에만 존재하고 Java는 다루지 않는다.

**Tech Stack:** Java 17 / Spring Boot(web·data-jpa) · Jackson · RestTemplate · H2(local·test)/PostgreSQL(prod) · JUnit5 + Mockito + AssertJ · Python 3.9+ (dhapi, Playwright, python-dotenv)

## Global Constraints

- **패키지 루트:** `com.canagent`. 신규 코드는 기존 레이아웃(`domain/`, `repository/`, `port/`, `port/dto/`, `service/`, `worker/`, `config/`) 관례를 따른다.
- **주석·로그·알림 메시지는 한국어**로 작성한다 (기존 코드 관례).
- **테스트 관례:** JUnit5 `@DisplayName`(한국어) + AssertJ `assertThat` + Mockito. 실행 `./gradlew test`.
- **JPA `ddl-auto: update`** — 엔티티가 H2(local/test)·PG(prod) 테이블을 자동 생성한다. prod용 명시 마이그레이션 SQL도 함께 제공한다(`scripts/db/`).
- **안전 게이트(중대):** `lottery.enabled` 기본 false, `lottery.dry-run` 기본 true. 실구매·프로덕션 활성화·배포는 사용자 승인 필수(CLAUDE.md).
- **커밋:** 각 Task 종료 시 커밋. 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **사이드카 ↔ Java JSON 계약 (권위 정본, Task 2·Task 11 공유):** 사이드카는 stdout에 **오직 한 줄의 JSON**을 출력한다. `numbers`는 사이드카가 이미 정규화한 **문자열**이다 — 로또는 `"3,7,12,25,33,41"`(오름차순 CSV), 연금은 `"조:6자리"` 예 `"3:123456"`.
  ```json
  {"ok": true, "balanceAfter": 6000,
   "tickets": [
     {"gameType": "LOTTO645", "roundNo": 1181, "numbers": "3,7,12,25,33,41", "amount": 1000},
     {"gameType": "WIN720",   "roundNo": 240,  "numbers": "3:123456",         "amount": 1000}],
   "errors": []}
  ```
  실패 시: `"ok": false`, `errors: [{"gameType":"WIN720","reason":"로그인 실패"}]`. `balance` 서브커맨드와 `--dry-run`은 구매 없이 `tickets: []` + 현재 `balanceAfter`를 반환한다.
- **연금복권720+ 번호 구조:** 조(1~5) + 6자리 숫자(000000~999999). (7자리 아님)

---

### Task 1: LotteryTicket 엔티티 · GameType · 리포지토리

구매 기록을 저장하고 당첨확인 시 갱신하는 영속 계층. 주간 멱등성(중복구매 방지)과 미확인 티켓 조회를 위한 파인더를 포함한다.

**Files:**
- Create: `src/main/java/com/canagent/domain/lottery/GameType.java`
- Create: `src/main/java/com/canagent/domain/lottery/LotteryTicket.java`
- Create: `src/main/java/com/canagent/repository/LotteryTicketRepository.java`
- Test: `src/test/java/com/canagent/repository/LotteryTicketRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum GameType { LOTTO645, WIN720 }`
  - `LotteryTicket(GameType gameType, int roundNo, String numbers, int amount, LocalDateTime purchasedAt)` 생성자
  - `void LotteryTicket.applyResult(Integer rank, String prizeLabel)` — rank/prizeLabel 설정, `winner = rank != null && rank > 0`, `resultChecked = true`
  - getters: `getId, getGameType, getRoundNo, getNumbers, getAmount, getPurchasedAt, isResultChecked, getRank, getPrizeLabel, isWinner`
  - `boolean LotteryTicketRepository.existsByGameTypeAndPurchasedAtAfter(GameType, LocalDateTime)`
  - `List<LotteryTicket> LotteryTicketRepository.findByGameTypeAndResultCheckedFalse(GameType)`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

```java
package com.canagent.repository;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("복권 티켓 리포지토리 단위테스트")
class LotteryTicketRepositoryTest {

    @Autowired
    private LotteryTicketRepository repository;

    @Test
    @DisplayName("이번 주 구매분 존재 여부를 게임별로 판정한다")
    void existsByGameTypeAndPurchasedAtAfter() {
        LocalDateTime weekStart = LocalDateTime.of(2026, 7, 13, 0, 0);
        repository.save(new LotteryTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000,
                LocalDateTime.of(2026, 7, 14, 9, 0)));

        assertThat(repository.existsByGameTypeAndPurchasedAtAfter(GameType.LOTTO645, weekStart)).isTrue();
        assertThat(repository.existsByGameTypeAndPurchasedAtAfter(GameType.WIN720, weekStart)).isFalse();
    }

    @Test
    @DisplayName("미확인 티켓을 게임별로 조회한다")
    void findByGameTypeAndResultCheckedFalse() {
        LotteryTicket t = new LotteryTicket(GameType.WIN720, 240, "3:123456", 1000, LocalDateTime.now());
        repository.save(t);

        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720);
        assertThat(pending).hasSize(1);

        pending.get(0).applyResult(0, "미당첨");
        repository.save(pending.get(0));
        assertThat(repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.repository.LotteryTicketRepositoryTest"`
Expected: FAIL — `GameType`/`LotteryTicket`/`LotteryTicketRepository` 컴파일 불가.

- [ ] **Step 3: GameType 작성**

```java
package com.canagent.domain.lottery;

public enum GameType {
    LOTTO645,  // 로또6/45
    WIN720     // 연금복권720+
}
```

- [ ] **Step 4: LotteryTicket 엔티티 작성**

```java
package com.canagent.domain.lottery;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "lottery_ticket")
public class LotteryTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GameType gameType;

    @Column(nullable = false)
    private int roundNo;              // 회차

    // 정규화 문자열: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
    @Column(nullable = false, length = 64)
    private String numbers;

    @Column(nullable = false)
    private int amount;               // 구매 금액(원)

    @Column(nullable = false)
    private LocalDateTime purchasedAt;

    @Column(nullable = false)
    private boolean resultChecked = false;

    private Integer rank;             // null=미확인, 0=미당첨, 1..=등수(연금 보너스=8)

    @Column(length = 64)
    private String prizeLabel;        // 예 "1등 (월 700만원 × 20년)"

    @Column(nullable = false)
    private boolean winner = false;

    protected LotteryTicket() {}

    public LotteryTicket(GameType gameType, int roundNo, String numbers, int amount, LocalDateTime purchasedAt) {
        this.gameType = gameType;
        this.roundNo = roundNo;
        this.numbers = numbers;
        this.amount = amount;
        this.purchasedAt = purchasedAt;
    }

    /** 당첨확인 결과 반영. rank>0 이면 당첨. */
    public void applyResult(Integer rank, String prizeLabel) {
        this.rank = rank;
        this.prizeLabel = prizeLabel;
        this.winner = rank != null && rank > 0;
        this.resultChecked = true;
    }

    public Long getId() { return id; }
    public GameType getGameType() { return gameType; }
    public int getRoundNo() { return roundNo; }
    public String getNumbers() { return numbers; }
    public int getAmount() { return amount; }
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
    public boolean isResultChecked() { return resultChecked; }
    public Integer getRank() { return rank; }
    public String getPrizeLabel() { return prizeLabel; }
    public boolean isWinner() { return winner; }
}
```

- [ ] **Step 5: 리포지토리 작성**

```java
package com.canagent.repository;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LotteryTicketRepository extends JpaRepository<LotteryTicket, Long> {

    boolean existsByGameTypeAndPurchasedAtAfter(GameType gameType, LocalDateTime after);

    List<LotteryTicket> findByGameTypeAndResultCheckedFalse(GameType gameType);
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.repository.LotteryTicketRepositoryTest"`
Expected: PASS (2 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/canagent/domain/lottery/ src/main/java/com/canagent/repository/LotteryTicketRepository.java src/test/java/com/canagent/repository/LotteryTicketRepositoryTest.java
git commit -m "feat(lottery): LotteryTicket 엔티티·GameType·리포지토리"
```

---

### Task 2: 사이드카 포트 · DTO · 파이썬 어댑터 · 설정

Java가 파이썬 사이드카를 subprocess로 호출하는 경계. 순수 JSON 파싱 메서드를 단위테스트한다(프로세스 실행은 얇은 래퍼로 분리, 수동 검증).

**Files:**
- Create: `src/main/java/com/canagent/port/dto/PurchasedTicket.java`
- Create: `src/main/java/com/canagent/port/dto/SidecarError.java`
- Create: `src/main/java/com/canagent/port/dto/SidecarResult.java`
- Create: `src/main/java/com/canagent/port/LotterySidecarPort.java`
- Create: `src/main/java/com/canagent/config/LotteryConfig.java`
- Create: `src/main/java/com/canagent/service/lottery/PythonLotterySidecarAdapter.java`
- Modify: `src/main/resources/application.yml` (lottery 블록 추가)
- Test: `src/test/java/com/canagent/service/lottery/PythonLotterySidecarAdapterTest.java`

**Interfaces:**
- Consumes: `GameType` (Task 1)
- Produces:
  - `record PurchasedTicket(GameType gameType, int roundNo, String numbers, int amount)`
  - `record SidecarError(String gameType, String reason)`
  - `record SidecarResult(boolean ok, int balanceAfter, List<PurchasedTicket> tickets, List<SidecarError> errors)`
  - `interface LotterySidecarPort { SidecarResult purchaseWeekly(List<GameType> games); int getBalance(); }`
  - `SidecarResult PythonLotterySidecarAdapter.parseSidecarJson(String json)` (package-private, 테스트 대상)
  - `LotteryConfig` getters: `isEnabled, isDryRun, getBalanceThreshold, getSidecarCommand()→List<String>, getSidecarTimeoutSec`

- [ ] **Step 1: 실패하는 파싱 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.dto.SidecarResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("파이썬 사이드카 어댑터 JSON 파싱 단위테스트")
class PythonLotterySidecarAdapterTest {

    private final PythonLotterySidecarAdapter adapter =
            new PythonLotterySidecarAdapter(new LotteryConfig(), new ObjectMapper());

    @Test
    @DisplayName("정상 구매 JSON을 SidecarResult로 파싱한다")
    void parseSuccess() {
        String json = "{\"ok\":true,\"balanceAfter\":6000,\"tickets\":["
                + "{\"gameType\":\"LOTTO645\",\"roundNo\":1181,\"numbers\":\"3,7,12,25,33,41\",\"amount\":1000},"
                + "{\"gameType\":\"WIN720\",\"roundNo\":240,\"numbers\":\"3:123456\",\"amount\":1000}],"
                + "\"errors\":[]}";

        SidecarResult r = adapter.parseSidecarJson(json);

        assertThat(r.ok()).isTrue();
        assertThat(r.balanceAfter()).isEqualTo(6000);
        assertThat(r.tickets()).hasSize(2);
        assertThat(r.tickets().get(0).gameType()).isEqualTo(GameType.LOTTO645);
        assertThat(r.tickets().get(0).numbers()).isEqualTo("3,7,12,25,33,41");
        assertThat(r.errors()).isEmpty();
    }

    @Test
    @DisplayName("실패 JSON의 errors를 파싱한다")
    void parseError() {
        String json = "{\"ok\":false,\"balanceAfter\":6000,\"tickets\":[],"
                + "\"errors\":[{\"gameType\":\"WIN720\",\"reason\":\"로그인 실패\"}]}";

        SidecarResult r = adapter.parseSidecarJson(json);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).reason()).isEqualTo("로그인 실패");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.PythonLotterySidecarAdapterTest"`
Expected: FAIL — 타입 컴파일 불가.

- [ ] **Step 3: DTO 레코드 3종 작성**

```java
// port/dto/PurchasedTicket.java
package com.canagent.port.dto;

import com.canagent.domain.lottery.GameType;

public record PurchasedTicket(GameType gameType, int roundNo, String numbers, int amount) {}
```
```java
// port/dto/SidecarError.java
package com.canagent.port.dto;

public record SidecarError(String gameType, String reason) {}
```
```java
// port/dto/SidecarResult.java
package com.canagent.port.dto;

import java.util.List;

public record SidecarResult(boolean ok, int balanceAfter,
                            List<PurchasedTicket> tickets, List<SidecarError> errors) {}
```

- [ ] **Step 4: 포트 인터페이스 작성**

```java
package com.canagent.port;

import com.canagent.domain.lottery.GameType;
import com.canagent.port.dto.SidecarResult;

import java.util.List;

/**
 * 복권 구매 사이드카 경계 포트. 구현: PythonLotterySidecarAdapter(prod), 테스트는 Fake.
 * dhlottery 로그인·주문·예치금 조회 등 인증·취약 영역만 사이드카(파이썬)에 위임한다.
 */
public interface LotterySidecarPort {

    /** 지정 게임만 구매하고 결과·구매후잔액을 반환. */
    SidecarResult purchaseWeekly(List<GameType> games);

    /** 예치금 잔액만 조회(구매 없음). */
    int getBalance();
}
```

- [ ] **Step 5: LotteryConfig 작성**

```java
package com.canagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "lottery")
public class LotteryConfig {

    private boolean enabled = false;         // 기능 전체 게이트(안전)
    private boolean dryRun = true;           // true면 구매 없이 로그인·잔액만
    private int balanceThreshold = 3000;     // 예치금 알림 임계(원)
    private List<String> sidecarCommand = new ArrayList<>();  // 예: [python3, /opt/canagent/sidecar/lottery/buy.py]
    private int sidecarTimeoutSec = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public int getBalanceThreshold() { return balanceThreshold; }
    public void setBalanceThreshold(int balanceThreshold) { this.balanceThreshold = balanceThreshold; }
    public List<String> getSidecarCommand() { return sidecarCommand; }
    public void setSidecarCommand(List<String> sidecarCommand) { this.sidecarCommand = sidecarCommand; }
    public int getSidecarTimeoutSec() { return sidecarTimeoutSec; }
    public void setSidecarTimeoutSec(int sidecarTimeoutSec) { this.sidecarTimeoutSec = sidecarTimeoutSec; }
}
```

- [ ] **Step 6: PythonLotterySidecarAdapter 작성**

```java
package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PythonLotterySidecarAdapter implements LotterySidecarPort {

    private static final Logger log = LoggerFactory.getLogger(PythonLotterySidecarAdapter.class);

    private final LotteryConfig config;
    private final ObjectMapper mapper;

    public PythonLotterySidecarAdapter(LotteryConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public SidecarResult purchaseWeekly(List<GameType> games) {
        List<String> cmd = new ArrayList<>(config.getSidecarCommand());
        cmd.add("buy");
        cmd.add("--games");
        cmd.add(games.stream().map(Enum::name).collect(Collectors.joining(",")));
        if (config.isDryRun()) cmd.add("--dry-run");
        return runAndParse(cmd);
    }

    @Override
    public int getBalance() {
        List<String> cmd = new ArrayList<>(config.getSidecarCommand());
        cmd.add("balance");
        return runAndParse(cmd).balanceAfter();
    }

    private SidecarResult runAndParse(List<String> cmd) {
        try {
            String out = runProcess(cmd);
            return parseSidecarJson(out);
        } catch (Exception e) {
            log.error("사이드카 실행 실패: {}", e.getMessage());
            return new SidecarResult(false, 0, List.of(),
                    List.of(new SidecarError("ALL", "사이드카 실행 실패: " + e.getMessage())));
        }
    }

    private String runProcess(List<String> cmd) throws Exception {
        log.info("복권 사이드카 실행: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes());
        boolean done = process.waitFor(config.getSidecarTimeoutSec(), TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IllegalStateException("사이드카 타임아웃(" + config.getSidecarTimeoutSec() + "s)");
        }
        // 사이드카는 JSON을 마지막 줄에 출력한다(로그와 분리).
        String[] lines = stdout.strip().split("\\R");
        return lines[lines.length - 1];
    }

    /** stdout JSON → SidecarResult. package-private(테스트 대상). */
    SidecarResult parseSidecarJson(String json) {
        try {
            return mapper.readValue(json, SidecarResult.class);
        } catch (Exception e) {
            log.error("사이드카 JSON 파싱 실패: {} / raw={}", e.getMessage(), json);
            return new SidecarResult(false, 0, List.of(),
                    List.of(new SidecarError("ALL", "JSON 파싱 실패")));
        }
    }
}
```

- [ ] **Step 7: application.yml에 lottery 블록 추가**

`src/main/resources/application.yml` 최상위(예: `notification:` 블록 아래)에 추가:

```yaml
lottery:
  enabled: ${LOTTERY_ENABLED:false}     # 안전: 기본 비활성
  dry-run: ${LOTTERY_DRY_RUN:true}      # 안전: 기본 구매 안 함
  balance-threshold: 3000
  sidecar-command: ${LOTTERY_SIDECAR_CMD:}   # 예: "python3,/opt/canagent/sidecar/lottery/buy.py"
  sidecar-timeout-sec: 120
  buy-cron: "0 0 9 * * TUE"
  win720-result-cron: "0 15 19 * * THU"
  lotto-result-cron: "0 45 20 * * SAT"
```

- [ ] **Step 8: 파싱 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.PythonLotterySidecarAdapterTest"`
Expected: PASS (2 tests)

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/canagent/port/ src/main/java/com/canagent/config/LotteryConfig.java src/main/java/com/canagent/service/lottery/PythonLotterySidecarAdapter.java src/main/resources/application.yml src/test/java/com/canagent/service/lottery/PythonLotterySidecarAdapterTest.java
git commit -m "feat(lottery): 사이드카 포트·DTO·파이썬 어댑터·설정"
```

---

### Task 3: 텔레그램 일반 텍스트 알림 (기존 알림층 확장)

복권은 매매 이벤트가 아닌 일반 텍스트 알림이 필요하다. `NotificationService`에 `sendText(String)`를 추가하고 Console·Telegram 구현 및 라우터 fan-out을 확장한다. 텔레그램 HTTP 전송부를 `sendText`/`send(event)`가 공유하도록 리팩토링한다(매매 동작 불변).

**Files:**
- Modify: `src/main/java/com/canagent/service/notification/NotificationService.java`
- Modify: `src/main/java/com/canagent/service/notification/ConsoleNotificationService.java`
- Modify: `src/main/java/com/canagent/service/notification/TelegramNotificationService.java`
- Modify: `src/main/java/com/canagent/service/notification/NotificationServiceRouter.java`
- Test: `src/test/java/com/canagent/service/notification/NotificationServiceRouterTest.java` (기존 파일에 케이스 추가)

**Interfaces:**
- Produces:
  - `void NotificationService.sendText(String message)`
  - `void NotificationServiceRouter.sendText(String message)` — 활성 채널에 fan-out

- [ ] **Step 1: 라우터 sendText 실패 테스트 추가**

기존 `NotificationServiceRouterTest.java`에 아래 테스트를 추가한다(기존 import·클래스 유지):

```java
    @Test
    @org.junit.jupiter.api.DisplayName("sendText는 활성 채널에만 전달한다")
    void sendText_fansOutToEnabledOnly() {
        java.util.List<String> received = new java.util.ArrayList<>();
        NotificationService enabled = new NotificationService() {
            public void send(NotificationEvent e) {}
            public void sendText(String m) { received.add(m); }
            public String getChannelName() { return "on"; }
            public boolean isEnabled() { return true; }
        };
        NotificationService disabled = new NotificationService() {
            public void send(NotificationEvent e) {}
            public void sendText(String m) { received.add("SHOULD_NOT"); }
            public String getChannelName() { return "off"; }
            public boolean isEnabled() { return false; }
        };
        NotificationServiceRouter router = new NotificationServiceRouter(java.util.List.of(enabled, disabled));

        router.sendText("예치금 부족");

        org.assertj.core.api.Assertions.assertThat(received).containsExactly("예치금 부족");
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.notification.NotificationServiceRouterTest"`
Expected: FAIL — `sendText` 미정의(익명 클래스가 인터페이스 신규 메서드를 구현하나 router에 `sendText` 없음 → 컴파일 실패).

- [ ] **Step 3: NotificationService 인터페이스에 sendText 추가**

`NotificationService.java`:

```java
public interface NotificationService {

    void send(NotificationEvent event);

    /** 일반 텍스트 알림(복권 등 비매매 알림). */
    void sendText(String message);

    String getChannelName();

    boolean isEnabled();
}
```

- [ ] **Step 4: ConsoleNotificationService에 sendText 구현**

`ConsoleNotificationService.java`에 메서드 추가:

```java
    @Override
    public void sendText(String message) {
        log.info("===== 알림 =====\n{}", message);
    }
```

- [ ] **Step 5: TelegramNotificationService 전송부 공유화 + sendText**

`TelegramNotificationService.java`의 `send(NotificationEvent)` 본문에서 HTTP 전송 부분을 private `dispatch(String)`로 추출하고, `send`와 `sendText`가 공유하게 한다:

```java
    @Override
    public void send(NotificationEvent event) {
        if (!isEnabled()) return;
        dispatch(event.formatMessage());
    }

    @Override
    public void sendText(String message) {
        if (!isEnabled()) return;
        dispatch(message);
    }

    private void dispatch(String message) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage",
                    config.getTelegram().getBotToken());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String jsonBody = String.format(
                    "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"HTML\"}",
                    config.getTelegram().getChatId(),
                    escapeJson(message)
            );

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("텔레그램 알림 전송 성공");
            } else {
                log.warn("텔레그램 알림 전송 실패: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("텔레그램 알림 전송 오류: {}", e.getMessage());
        }
    }
```

(기존 `escapeJson`·`isEnabled`·`getChannelName`은 그대로 둔다.)

- [ ] **Step 6: NotificationServiceRouter에 sendText 추가**

`NotificationServiceRouter.java`에 메서드 추가:

```java
    public void sendText(String message) {
        for (NotificationService service : services) {
            if (service.isEnabled()) {
                try {
                    service.sendText(message);
                } catch (Exception e) {
                    log.error("텍스트 알림 전송 실패 ({}): {}",
                            service.getChannelName(), e.getMessage());
                }
            }
        }
    }
```

- [ ] **Step 7: 알림 관련 전체 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.notification.*"`
Expected: PASS (기존 케이스 전부 + 신규 sendText 케이스). 기존 매매 알림 테스트가 깨지지 않아야 한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/canagent/service/notification/ src/test/java/com/canagent/service/notification/NotificationServiceRouterTest.java
git commit -m "feat(notification): 일반 텍스트 알림(sendText) 확장 + 텔레그램 전송부 공유화"
```

---

### Task 4: LotteryPurchaseService (구매 오케스트레이션 · 멱등성 · 예치금 알림)

주간 구매 핵심 로직. 이번 주 미구매 게임만 사이드카에 요청(멱등성), 결과 저장, 구매·오류·예치금부족 알림.

**Files:**
- Create: `src/main/java/com/canagent/service/lottery/LotteryPurchaseService.java`
- Test: `src/test/java/com/canagent/service/lottery/LotteryPurchaseServiceTest.java`

**Interfaces:**
- Consumes: `LotterySidecarPort` (Task 2), `LotteryTicketRepository` (Task 1), `NotificationServiceRouter.sendText` (Task 3), `LotteryConfig` (Task 2), `Clock` (기존 `SchedulingConfig.systemClock` 빈, UTC)
- Produces: `void LotteryPurchaseService.buyWeekly()`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.PurchasedTicket;
import com.canagent.port.dto.SidecarResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("복권 주간 구매 서비스 단위테스트")
class LotteryPurchaseServiceTest {

    private LotteryTicketRepository repo;
    private NotificationServiceRouter router;
    private LotteryConfig config;
    private final List<String> messages = new ArrayList<>();
    // 2026-07-14(화) 09:00 KST = 2026-07-14T00:00:00Z
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        repo = mock(LotteryTicketRepository.class);
        router = mock(NotificationServiceRouter.class);
        config = new LotteryConfig();
        config.setBalanceThreshold(3000);
        messages.clear();
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; })
                .when(router).sendText(anyString());
    }

    @Test
    @DisplayName("이번 주 미구매 게임만 구매하고 티켓을 저장·알림한다")
    void buysPendingAndNotifies() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = games -> new SidecarResult(true, 8000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000),
                new PurchasedTicket(GameType.WIN720, 240, "3:123456", 1000)), List.of());
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        verify(repo, times(2)).save(any(LotteryTicket.class));
        assertThat(messages).anyMatch(m -> m.contains("구매"));
        assertThat(messages).noneMatch(m -> m.contains("예치금 부족"));
    }

    @Test
    @DisplayName("두 게임 모두 이번 주 구매됨이면 스킵한다")
    void skipsWhenAllPurchased() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(true);
        List<GameType> requested = new ArrayList<>();
        LotterySidecarPort port = games -> { requested.addAll(games); return new SidecarResult(true, 8000, List.of(), List.of()); };
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(requested).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("구매 후 잔액이 임계 미만이면 예치금 부족을 알린다")
    void alertsLowBalance() {
        when(repo.existsByGameTypeAndPurchasedAtAfter(any(), any())).thenReturn(false);
        LotterySidecarPort port = games -> new SidecarResult(true, 2000, List.of(
                new PurchasedTicket(GameType.LOTTO645, 1181, "3,7,12,25,33,41", 1000)), List.of());
        LotteryPurchaseService svc = new LotteryPurchaseService(port, repo, router, config, clock);

        svc.buyWeekly();

        assertThat(messages).anyMatch(m -> m.contains("예치금 부족"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryPurchaseServiceTest"`
Expected: FAIL — `LotteryPurchaseService` 미정의.

- [ ] **Step 3: LotteryPurchaseService 구현**

```java
package com.canagent.service.lottery;

import com.canagent.config.LotteryConfig;
import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.port.LotterySidecarPort;
import com.canagent.port.dto.PurchasedTicket;
import com.canagent.port.dto.SidecarError;
import com.canagent.port.dto.SidecarResult;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

@Service
public class LotteryPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(LotteryPurchaseService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LotterySidecarPort port;
    private final LotteryTicketRepository repository;
    private final NotificationServiceRouter router;
    private final LotteryConfig config;
    private final Clock clock;

    public LotteryPurchaseService(LotterySidecarPort port, LotteryTicketRepository repository,
                                  NotificationServiceRouter router, LotteryConfig config, Clock clock) {
        this.port = port;
        this.repository = repository;
        this.router = router;
        this.config = config;
        this.clock = clock;
    }

    public void buyWeekly() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDateTime weekStart = nowKst.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();

        List<GameType> pending = Arrays.stream(GameType.values())
                .filter(g -> !repository.existsByGameTypeAndPurchasedAtAfter(g, weekStart))
                .toList();

        if (pending.isEmpty()) {
            log.info("이번 주 복권 이미 구매됨 — 스킵");
            return;
        }

        log.info("복권 구매 요청: {}", pending);
        SidecarResult result = port.purchaseWeekly(pending);

        LocalDateTime purchasedAt = nowKst.toLocalDateTime();
        for (PurchasedTicket t : result.tickets()) {
            repository.save(new LotteryTicket(t.gameType(), t.roundNo(), t.numbers(), t.amount(), purchasedAt));
        }

        if (!result.tickets().isEmpty()) {
            router.sendText(formatPurchase(result));
        }
        for (SidecarError e : result.errors()) {
            router.sendText("❌ 복권 구매 실패: " + e.gameType() + " — " + e.reason());
        }
        if (result.balanceAfter() < config.getBalanceThreshold()) {
            router.sendText("⚠️ 예치금 부족: 현재 " + result.balanceAfter() + "원 (임계 "
                    + config.getBalanceThreshold() + "원). 충전이 필요합니다.");
        }
    }

    private String formatPurchase(SidecarResult result) {
        StringBuilder sb = new StringBuilder("🎫 복권 구매 완료\n");
        for (PurchasedTicket t : result.tickets()) {
            String name = t.gameType() == GameType.LOTTO645 ? "로또6/45" : "연금복권720+";
            sb.append("• ").append(name).append(" ").append(t.roundNo()).append("회 [")
              .append(t.numbers()).append("] ").append(t.amount()).append("원\n");
        }
        sb.append("• 잔액: ").append(result.balanceAfter()).append("원");
        return sb.toString();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryPurchaseServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/canagent/service/lottery/LotteryPurchaseService.java src/test/java/com/canagent/service/lottery/LotteryPurchaseServiceTest.java
git commit -m "feat(lottery): 주간 구매 서비스(멱등성·예치금 알림)"
```

---

### Task 5: LottoResultClient (로또 공개 API)

로또 당첨번호를 공개 JSON API로 조회한다. 순수 파싱 메서드를 픽스처로 테스트한다.

**Files:**
- Create: `src/main/java/com/canagent/service/lottery/LottoResultClient.java`
- Create: `src/main/java/com/canagent/service/lottery/dto/LottoDraw.java`
- Test: `src/test/java/com/canagent/service/lottery/LottoResultClientTest.java`

**Interfaces:**
- Produces:
  - `record LottoDraw(int roundNo, List<Integer> numbers, int bonus, long firstWinAmount, boolean success)`
  - `LottoDraw LottoResultClient.getWinningNumbers(int roundNo)`
  - `LottoDraw LottoResultClient.parse(String json)` (package-private, 테스트 대상)

- [ ] **Step 1: 실패하는 파싱 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로또 당첨 API 파싱 단위테스트")
class LottoResultClientTest {

    private final LottoResultClient client = new LottoResultClient(new RestTemplate(), new ObjectMapper());

    @Test
    @DisplayName("성공 응답을 LottoDraw로 파싱한다")
    void parseSuccess() {
        String json = "{\"returnValue\":\"success\",\"drwNo\":1100,\"drwtNo1\":3,\"drwtNo2\":7,"
                + "\"drwtNo3\":12,\"drwtNo4\":25,\"drwtNo5\":33,\"drwtNo6\":41,\"bnusNo\":10,"
                + "\"firstWinamnt\":2000000000}";

        LottoDraw d = client.parse(json);

        assertThat(d.success()).isTrue();
        assertThat(d.roundNo()).isEqualTo(1100);
        assertThat(d.numbers()).containsExactly(3, 7, 12, 25, 33, 41);
        assertThat(d.bonus()).isEqualTo(10);
        assertThat(d.firstWinAmount()).isEqualTo(2000000000L);
    }

    @Test
    @DisplayName("미추첨(fail) 응답은 success=false")
    void parseFail() {
        LottoDraw d = client.parse("{\"returnValue\":\"fail\"}");
        assertThat(d.success()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LottoResultClientTest"`
Expected: FAIL — 타입 미정의.

- [ ] **Step 3: LottoDraw 레코드 작성**

```java
package com.canagent.service.lottery.dto;

import java.util.List;

public record LottoDraw(int roundNo, List<Integer> numbers, int bonus, long firstWinAmount, boolean success) {}
```

- [ ] **Step 4: LottoResultClient 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class LottoResultClient {

    private static final Logger log = LoggerFactory.getLogger(LottoResultClient.class);
    private static final String URL =
            "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public LottoResultClient(RestTemplate restTemplate, ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    public LottoDraw getWinningNumbers(int roundNo) {
        String body = restTemplate.getForObject(URL + roundNo, String.class);
        return parse(body);
    }

    LottoDraw parse(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            boolean success = "success".equals(n.path("returnValue").asText());
            if (!success) {
                return new LottoDraw(0, List.of(), 0, 0, false);
            }
            List<Integer> nums = List.of(
                    n.path("drwtNo1").asInt(), n.path("drwtNo2").asInt(), n.path("drwtNo3").asInt(),
                    n.path("drwtNo4").asInt(), n.path("drwtNo5").asInt(), n.path("drwtNo6").asInt());
            return new LottoDraw(n.path("drwNo").asInt(), nums, n.path("bnusNo").asInt(),
                    n.path("firstWinamnt").asLong(), true);
        } catch (Exception e) {
            log.error("로또 당첨 파싱 실패: {}", e.getMessage());
            return new LottoDraw(0, List.of(), 0, 0, false);
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LottoResultClientTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/canagent/service/lottery/LottoResultClient.java src/main/java/com/canagent/service/lottery/dto/LottoDraw.java src/test/java/com/canagent/service/lottery/LottoResultClientTest.java
git commit -m "feat(lottery): 로또 당첨 공개 API 클라이언트"
```

---

### Task 6: Win720ResultClient (연금복권 당첨 조회 + 파서)

연금복권720+ 당첨 조/6자리/보너스를 공개 결과 페이지에서 조회한다. HTML 구조는 라이브 확인이 필요하므로 **파싱을 순수 함수로 분리**하고 실제 응답을 픽스처로 저장해 테스트한다.

> **구현 주의(라이브 확인 단계):** 연금 결과 페이지는 대기열/JS 렌더가 있을 수 있다. Step 3에서 실제 회차 페이지를 받아 픽스처로 저장하고, Step 5의 정규식을 **저장된 실제 HTML에 맞춰 조정**한다(정규식과 픽스처를 함께 수정). 아래 파서·픽스처는 조:6자리 + 보너스 6자리를 추출하는 출발점이다.

**Files:**
- Create: `src/main/java/com/canagent/service/lottery/Win720ResultClient.java`
- Create: `src/main/java/com/canagent/service/lottery/Win720Parser.java`
- Create: `src/main/java/com/canagent/service/lottery/dto/Win720Draw.java`
- Create: `src/test/resources/fixtures/win720-round240.html` (라이브 캡처로 교체)
- Test: `src/test/java/com/canagent/service/lottery/Win720ParserTest.java`

**Interfaces:**
- Produces:
  - `record Win720Draw(int roundNo, int jo, String digits, String bonusDigits, boolean success)` — `digits`/`bonusDigits`는 6자리 문자열
  - `Win720Draw Win720ResultClient.getWinningNumbers(int roundNo)`
  - `static Win720Draw Win720Parser.parse(int roundNo, String html)`

- [ ] **Step 1: 픽스처 초안 작성**

`src/test/resources/fixtures/win720-round240.html` (라이브 캡처 전 임시 — 아래 파서의 정규식과 일치):

```html
<html><body>
<div class="win720_number">
  <span class="jo">3조</span>
  <span class="num">1</span><span class="num">2</span><span class="num">3</span>
  <span class="num">4</span><span class="num">5</span><span class="num">6</span>
</div>
<div class="bonus_number">
  <span class="num">9</span><span class="num">8</span><span class="num">7</span>
  <span class="num">6</span><span class="num">5</span><span class="num">4</span>
</div>
</body></html>
```

- [ ] **Step 2: 실패하는 파서 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.Win720Draw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("연금복권 당첨 파서 단위테스트")
class Win720ParserTest {

    @Test
    @DisplayName("결과 HTML에서 조·6자리·보너스를 추출한다")
    void parse() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/win720-round240.html"));

        Win720Draw d = Win720Parser.parse(240, html);

        assertThat(d.success()).isTrue();
        assertThat(d.jo()).isEqualTo(3);
        assertThat(d.digits()).isEqualTo("123456");
        assertThat(d.bonusDigits()).isEqualTo("987654");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인 + 라이브 캡처**

Run: `./gradlew test --tests "com.canagent.service.lottery.Win720ParserTest"`
Expected: FAIL — `Win720Parser`/`Win720Draw` 미정의.

라이브 HTML을 캡처해 픽스처를 실제 구조로 교체한다(회차는 최근 값으로):
```bash
curl -s "https://www.dhlottery.co.kr/gameResult.do?method=win720&Round=240" -o src/test/resources/fixtures/win720-round240.html
```
캡처한 HTML을 열어 조·6자리·보너스가 담긴 실제 엘리먼트 구조를 확인하고, Step 1의 픽스처가 실제와 다르면 **Step 5 정규식과 이 픽스처를 함께 실제 구조에 맞춘다**. (테스트의 기대값 3조/123456/987654도 실제 회차 값으로 갱신)

- [ ] **Step 4: Win720Draw 레코드 작성**

```java
package com.canagent.service.lottery.dto;

public record Win720Draw(int roundNo, int jo, String digits, String bonusDigits, boolean success) {}
```

- [ ] **Step 5: Win720Parser 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.Win720Draw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 연금복권720+ 결과 HTML 파서(순수 함수). 정규식은 라이브 HTML 구조에 맞춰 조정한다. */
public final class Win720Parser {

    private static final Logger log = LoggerFactory.getLogger(Win720Parser.class);
    // "3조" 형태에서 조 번호 추출
    private static final Pattern JO = Pattern.compile("(\\d)\\s*조");

    private Win720Parser() {}

    public static Win720Draw parse(int roundNo, String html) {
        try {
            if (html == null || html.isBlank()) {
                return new Win720Draw(roundNo, 0, "", "", false);
            }
            Matcher joM = JO.matcher(html);
            int jo = joM.find() ? Integer.parseInt(joM.group(1)) : 0;

            // 당첨번호 블록·보너스 블록 각각에서 <span class="num">숫자</span> 6개 추출
            String digits = extractDigits(html, "win720_number");
            String bonus = extractDigits(html, "bonus_number");

            boolean success = jo >= 1 && jo <= 5 && digits.length() == 6;
            return new Win720Draw(roundNo, jo, digits, bonus, success);
        } catch (Exception e) {
            log.error("연금 당첨 파싱 실패: {}", e.getMessage());
            return new Win720Draw(roundNo, 0, "", "", false);
        }
    }

    private static String extractDigits(String html, String blockClass) {
        int start = html.indexOf(blockClass);
        if (start < 0) return "";
        // 해당 블록 이후 구간에서 <span class="num">d</span> 패턴의 숫자를 최대 6개 모은다.
        String region = html.substring(start);
        Matcher m = Pattern.compile("class=\"num\"[^>]*>\\s*(\\d)\\s*<").matcher(region);
        StringBuilder sb = new StringBuilder();
        while (m.find() && sb.length() < 6) {
            sb.append(m.group(1));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 6: Win720ResultClient 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.Win720Draw;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class Win720ResultClient {

    private static final String URL =
            "https://www.dhlottery.co.kr/gameResult.do?method=win720&Round=";

    private final RestTemplate restTemplate;

    public Win720ResultClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Win720Draw getWinningNumbers(int roundNo) {
        String html = restTemplate.getForObject(URL + roundNo, String.class);
        return Win720Parser.parse(roundNo, html);
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.Win720ParserTest"`
Expected: PASS (1 test) — 라이브 픽스처·기대값 갱신 후에도 통과.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/canagent/service/lottery/Win720ResultClient.java src/main/java/com/canagent/service/lottery/Win720Parser.java src/main/java/com/canagent/service/lottery/dto/Win720Draw.java src/test/resources/fixtures/win720-round240.html src/test/java/com/canagent/service/lottery/Win720ParserTest.java
git commit -m "feat(lottery): 연금복권 당첨 조회 클라이언트·파서"
```

---

### Task 7: LotteryRankCalculator · LotteryPrizeFormatter (등수·상금 계산)

당첨 판정의 핵심 순수 로직. 로또·연금 등수 규칙과 상금 라벨을 픽스처로 TDD한다.

**Files:**
- Create: `src/main/java/com/canagent/service/lottery/LotteryRankCalculator.java`
- Create: `src/main/java/com/canagent/service/lottery/LotteryPrizeFormatter.java`
- Test: `src/test/java/com/canagent/service/lottery/LotteryRankCalculatorTest.java`

**Interfaces:**
- Consumes: `LottoDraw` (Task 5), `Win720Draw` (Task 6)
- Produces:
  - `static int LotteryRankCalculator.lottoRank(List<Integer> ticket, LottoDraw draw)` — 1~5, 없으면 0
  - `static int LotteryRankCalculator.win720Rank(int jo, String digits, Win720Draw draw)` — 1~7, 보너스=8, 없으면 0
  - `static String LotteryPrizeFormatter.lotto(int rank, LottoDraw draw)`
  - `static String LotteryPrizeFormatter.win720(int rank)`

**등수 규칙 (Global Constraints의 번호 구조 기준):**
- 로또: 6개 중 일치수 m, 보너스 포함 b → 1등=m6, 2등=m5+b, 3등=m5, 4등=m4, 5등=m3.
- 연금: 조 일치 여부 + 뒤에서 연속 일치 자릿수 s(0~6) → 1등=s6+조, 2등=s6, 보너스=digits==bonusDigits, 3등=s5, 4등=s4, 5등=s3, 6등=s2, 7등=s1.

- [ ] **Step 1: 실패하는 계산 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("복권 등수·상금 계산 단위테스트")
class LotteryRankCalculatorTest {

    private final LottoDraw lotto = new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true);

    @Test
    @DisplayName("로또 1등: 6개 일치")
    void lottoFirst() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 41), lotto)).isEqualTo(1);
    }

    @Test
    @DisplayName("로또 2등: 5개+보너스")
    void lottoSecond() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 10), lotto)).isEqualTo(2);
    }

    @Test
    @DisplayName("로또 3등: 5개(보너스 없음)")
    void lottoThird() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 25, 33, 44), lotto)).isEqualTo(3);
    }

    @Test
    @DisplayName("로또 5등: 3개 일치")
    void lottoFifth() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 12, 44, 45, 2), lotto)).isEqualTo(5);
    }

    @Test
    @DisplayName("로또 미당첨: 2개 일치")
    void lottoNone() {
        assertThat(LotteryRankCalculator.lottoRank(List.of(3, 7, 1, 2, 44, 45), lotto)).isEqualTo(0);
    }

    private final Win720Draw win = new Win720Draw(240, 3, "123456", "987654", true);

    @Test
    @DisplayName("연금 1등: 조+6자리")
    void winFirst() {
        assertThat(LotteryRankCalculator.win720Rank(3, "123456", win)).isEqualTo(1);
    }

    @Test
    @DisplayName("연금 2등: 조 다르고 6자리 일치")
    void winSecond() {
        assertThat(LotteryRankCalculator.win720Rank(2, "123456", win)).isEqualTo(2);
    }

    @Test
    @DisplayName("연금 3등: 뒤 5자리 일치")
    void winThird() {
        assertThat(LotteryRankCalculator.win720Rank(1, "923456", win)).isEqualTo(3);
    }

    @Test
    @DisplayName("연금 7등: 뒤 1자리 일치")
    void winSeventh() {
        assertThat(LotteryRankCalculator.win720Rank(1, "999996", win)).isEqualTo(7);
    }

    @Test
    @DisplayName("연금 보너스: 보너스번호 6자리 일치")
    void winBonus() {
        assertThat(LotteryRankCalculator.win720Rank(1, "987654", win)).isEqualTo(8);
    }

    @Test
    @DisplayName("상금 라벨: 로또 1등은 당첨금 포함")
    void prizeLabels() {
        assertThat(LotteryPrizeFormatter.lotto(1, lotto)).contains("1등");
        assertThat(LotteryPrizeFormatter.lotto(5, lotto)).contains("5등");
        assertThat(LotteryPrizeFormatter.lotto(0, lotto)).isEqualTo("미당첨");
        assertThat(LotteryPrizeFormatter.win720(1)).contains("1등");
        assertThat(LotteryPrizeFormatter.win720(8)).contains("보너스");
        assertThat(LotteryPrizeFormatter.win720(0)).isEqualTo("미당첨");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryRankCalculatorTest"`
Expected: FAIL — 클래스 미정의.

- [ ] **Step 3: LotteryRankCalculator 작성**

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;

import java.util.List;

public final class LotteryRankCalculator {

    private LotteryRankCalculator() {}

    /** 로또 등수(1~5), 미당첨 0. */
    public static int lottoRank(List<Integer> ticket, LottoDraw draw) {
        long m = ticket.stream().filter(draw.numbers()::contains).count();
        boolean bonus = ticket.contains(draw.bonus());
        if (m == 6) return 1;
        if (m == 5 && bonus) return 2;
        if (m == 5) return 3;
        if (m == 4) return 4;
        if (m == 3) return 5;
        return 0;
    }

    /** 연금 등수(1~7), 보너스 8, 미당첨 0. */
    public static int win720Rank(int jo, String digits, Win720Draw draw) {
        int s = commonSuffixLength(digits, draw.digits());
        if (s == 6 && jo == draw.jo()) return 1;
        if (s == 6) return 2;
        if (digits.equals(draw.bonusDigits())) return 8;   // 보너스
        if (s == 5) return 3;
        if (s == 4) return 4;
        if (s == 3) return 5;
        if (s == 2) return 6;
        if (s == 1) return 7;
        return 0;
    }

    private static int commonSuffixLength(String a, String b) {
        int i = a.length() - 1, j = b.length() - 1, n = 0;
        while (i >= 0 && j >= 0 && a.charAt(i) == b.charAt(j)) { i--; j--; n++; }
        return n;
    }
}
```

- [ ] **Step 4: LotteryPrizeFormatter 작성**

> 상금 금액은 동행복권 공지 기준 근사값이다. 실제 지급액과 다를 수 있어 라벨은 참고용이다.

```java
package com.canagent.service.lottery;

import com.canagent.service.lottery.dto.LottoDraw;

import java.text.DecimalFormat;

public final class LotteryPrizeFormatter {

    private static final DecimalFormat WON = new DecimalFormat("#,##0");

    private LotteryPrizeFormatter() {}

    public static String lotto(int rank, LottoDraw draw) {
        return switch (rank) {
            case 1 -> "1등 (" + WON.format(draw.firstWinAmount()) + "원)";
            case 2 -> "2등";
            case 3 -> "3등";
            case 4 -> "4등 (50,000원)";
            case 5 -> "5등 (5,000원)";
            default -> "미당첨";
        };
    }

    public static String win720(int rank) {
        return switch (rank) {
            case 1 -> "1등 (월 700만원 × 20년)";
            case 2 -> "2등 (월 100만원 × 10년)";
            case 3 -> "3등 (100만원)";
            case 4 -> "4등 (10만원)";
            case 5 -> "5등 (5만원)";
            case 6 -> "6등 (5천원)";
            case 7 -> "7등 (1천원)";
            case 8 -> "보너스 (월 100만원 × 10년)";
            default -> "미당첨";
        };
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryRankCalculatorTest"`
Expected: PASS (11 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/canagent/service/lottery/LotteryRankCalculator.java src/main/java/com/canagent/service/lottery/LotteryPrizeFormatter.java src/test/java/com/canagent/service/lottery/LotteryRankCalculatorTest.java
git commit -m "feat(lottery): 등수·상금 계산 로직"
```

---

### Task 8: LotteryResultService (당첨확인 오케스트레이션)

미확인 티켓의 회차 당첨번호를 조회→등수 계산→저장→알림한다.

**Files:**
- Create: `src/main/java/com/canagent/service/lottery/LotteryResultService.java`
- Test: `src/test/java/com/canagent/service/lottery/LotteryResultServiceTest.java`

**Interfaces:**
- Consumes: `LotteryTicketRepository`(Task 1), `LottoResultClient`(Task 5), `Win720ResultClient`(Task 6), `LotteryRankCalculator`·`LotteryPrizeFormatter`(Task 7), `NotificationServiceRouter`(Task 3)
- Produces:
  - `void LotteryResultService.checkLotto()`
  - `void LotteryResultService.checkWin720()`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

```java
package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
import com.canagent.service.notification.NotificationServiceRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("복권 당첨확인 서비스 단위테스트")
class LotteryResultServiceTest {

    private LotteryTicketRepository repo;
    private LottoResultClient lottoClient;
    private Win720ResultClient winClient;
    private NotificationServiceRouter router;
    private final List<String> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(LotteryTicketRepository.class);
        lottoClient = mock(LottoResultClient.class);
        winClient = mock(Win720ResultClient.class);
        router = mock(NotificationServiceRouter.class);
        messages.clear();
        doAnswer(inv -> { messages.add(inv.getArgument(0)); return null; }).when(router).sendText(anyString());
    }

    @Test
    @DisplayName("로또 당첨확인: 등수 반영·저장·알림")
    void checkLotto() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        when(lottoClient.getWinningNumbers(1100))
                .thenReturn(new LottoDraw(1100, List.of(3, 7, 12, 25, 33, 41), 10, 2_000_000_000L, true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        assertThat(t.getRank()).isEqualTo(1);
        assertThat(t.isResultChecked()).isTrue();
        verify(repo).save(t);
        assertThat(messages).anyMatch(m -> m.contains("1등"));
    }

    @Test
    @DisplayName("미추첨(success=false)이면 확인·저장하지 않는다")
    void skipWhenNotDrawn() {
        LotteryTicket t = new LotteryTicket(GameType.LOTTO645, 1100, "3,7,12,25,33,41", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645)).thenReturn(List.of(t));
        when(lottoClient.getWinningNumbers(1100)).thenReturn(new LottoDraw(0, List.of(), 0, 0, false));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkLotto();

        assertThat(t.isResultChecked()).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("연금 당첨확인: 조:6자리 파싱·등수 반영")
    void checkWin720() {
        LotteryTicket t = new LotteryTicket(GameType.WIN720, 240, "3:123456", 1000, LocalDateTime.now());
        when(repo.findByGameTypeAndResultCheckedFalse(GameType.WIN720)).thenReturn(List.of(t));
        when(winClient.getWinningNumbers(240)).thenReturn(new Win720Draw(240, 3, "123456", "987654", true));

        LotteryResultService svc = new LotteryResultService(repo, lottoClient, winClient, router);
        svc.checkWin720();

        assertThat(t.getRank()).isEqualTo(1);
        verify(repo).save(t);
        assertThat(messages).anyMatch(m -> m.contains("1등"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryResultServiceTest"`
Expected: FAIL — `LotteryResultService` 미정의.

- [ ] **Step 3: LotteryResultService 구현**

```java
package com.canagent.service.lottery;

import com.canagent.domain.lottery.GameType;
import com.canagent.domain.lottery.LotteryTicket;
import com.canagent.repository.LotteryTicketRepository;
import com.canagent.service.lottery.dto.LottoDraw;
import com.canagent.service.lottery.dto.Win720Draw;
import com.canagent.service.notification.NotificationServiceRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LotteryResultService {

    private static final Logger log = LoggerFactory.getLogger(LotteryResultService.class);

    private final LotteryTicketRepository repository;
    private final LottoResultClient lottoClient;
    private final Win720ResultClient win720Client;
    private final NotificationServiceRouter router;

    public LotteryResultService(LotteryTicketRepository repository, LottoResultClient lottoClient,
                                Win720ResultClient win720Client, NotificationServiceRouter router) {
        this.repository = repository;
        this.lottoClient = lottoClient;
        this.win720Client = win720Client;
        this.router = router;
    }

    public void checkLotto() {
        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.LOTTO645);
        Map<Integer, LottoDraw> cache = new HashMap<>();
        StringBuilder summary = new StringBuilder();

        for (LotteryTicket t : pending) {
            LottoDraw draw = cache.computeIfAbsent(t.getRoundNo(), lottoClient::getWinningNumbers);
            if (!draw.success()) continue;   // 아직 미추첨
            List<Integer> nums = Arrays.stream(t.getNumbers().split(",")).map(Integer::parseInt).toList();
            int rank = LotteryRankCalculator.lottoRank(nums, draw);
            String label = LotteryPrizeFormatter.lotto(rank, draw);
            t.applyResult(rank, label);
            repository.save(t);
            summary.append("• 로또 ").append(t.getRoundNo()).append("회 [").append(t.getNumbers())
                   .append("] → ").append(label).append("\n");
        }
        if (summary.length() > 0) {
            router.sendText("🎯 로또 당첨확인\n" + summary);
        } else {
            log.info("로또 당첨확인: 대상 없음");
        }
    }

    public void checkWin720() {
        List<LotteryTicket> pending = repository.findByGameTypeAndResultCheckedFalse(GameType.WIN720);
        Map<Integer, Win720Draw> cache = new HashMap<>();
        StringBuilder summary = new StringBuilder();

        for (LotteryTicket t : pending) {
            Win720Draw draw = cache.computeIfAbsent(t.getRoundNo(), win720Client::getWinningNumbers);
            if (!draw.success()) continue;
            String[] parts = t.getNumbers().split(":");   // "조:6자리"
            int jo = Integer.parseInt(parts[0]);
            String digits = parts[1];
            int rank = LotteryRankCalculator.win720Rank(jo, digits, draw);
            String label = LotteryPrizeFormatter.win720(rank);
            t.applyResult(rank, label);
            repository.save(t);
            summary.append("• 연금 ").append(t.getRoundNo()).append("회 [").append(t.getNumbers())
                   .append("] → ").append(label).append("\n");
        }
        if (summary.length() > 0) {
            router.sendText("🎯 연금복권 당첨확인\n" + summary);
        } else {
            log.info("연금 당첨확인: 대상 없음");
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.service.lottery.LotteryResultServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/canagent/service/lottery/LotteryResultService.java src/test/java/com/canagent/service/lottery/LotteryResultServiceTest.java
git commit -m "feat(lottery): 당첨확인 오케스트레이션 서비스"
```

---

### Task 9: LotteryScheduler (@Scheduled, Asia/Seoul)

크론으로 서비스를 트리거하는 얇은 워커. `lottery.enabled=true`일 때만 빈 등록.

**Files:**
- Create: `src/main/java/com/canagent/worker/LotteryScheduler.java`
- Test: `src/test/java/com/canagent/worker/LotterySchedulerTest.java`

**Interfaces:**
- Consumes: `LotteryPurchaseService`(Task 4), `LotteryResultService`(Task 8)
- Produces: `LotteryScheduler.buy()`, `.checkWin720()`, `.checkLotto()`

- [ ] **Step 1: 실패하는 위임 테스트 작성**

```java
package com.canagent.worker;

import com.canagent.service.lottery.LotteryPurchaseService;
import com.canagent.service.lottery.LotteryResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

@DisplayName("복권 스케줄러 위임 단위테스트")
class LotterySchedulerTest {

    @Test
    @DisplayName("각 크론 메서드가 해당 서비스에 위임한다")
    void delegates() {
        LotteryPurchaseService purchase = mock(LotteryPurchaseService.class);
        LotteryResultService result = mock(LotteryResultService.class);
        LotteryScheduler scheduler = new LotteryScheduler(purchase, result);

        scheduler.buy();
        scheduler.checkWin720();
        scheduler.checkLotto();

        verify(purchase).buyWeekly();
        verify(result).checkWin720();
        verify(result).checkLotto();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.canagent.worker.LotterySchedulerTest"`
Expected: FAIL — `LotteryScheduler` 미정의.

- [ ] **Step 3: LotteryScheduler 작성**

```java
package com.canagent.worker;

import com.canagent.service.lottery.LotteryPurchaseService;
import com.canagent.service.lottery.LotteryResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lottery.enabled", havingValue = "true")
public class LotteryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LotteryScheduler.class);

    private final LotteryPurchaseService purchaseService;
    private final LotteryResultService resultService;

    public LotteryScheduler(LotteryPurchaseService purchaseService, LotteryResultService resultService) {
        this.purchaseService = purchaseService;
        this.resultService = resultService;
    }

    // 주간 구매: 매주 화 09:00 KST (두 추첨일 전에 미리)
    @Scheduled(cron = "${lottery.buy-cron:0 0 9 * * TUE}", zone = "Asia/Seoul")
    public void buy() {
        log.info("===== 복권 주간 구매 =====");
        purchaseService.buyWeekly();
    }

    // 연금 추첨: 목 19:05 → 19:15 확인
    @Scheduled(cron = "${lottery.win720-result-cron:0 15 19 * * THU}", zone = "Asia/Seoul")
    public void checkWin720() {
        log.info("===== 연금복권 당첨확인 =====");
        resultService.checkWin720();
    }

    // 로또 추첨: 토 20:35 → 20:45 확인
    @Scheduled(cron = "${lottery.lotto-result-cron:0 45 20 * * SAT}", zone = "Asia/Seoul")
    public void checkLotto() {
        log.info("===== 로또 당첨확인 =====");
        resultService.checkLotto();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.canagent.worker.LotterySchedulerTest"`
Expected: PASS (1 test)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/canagent/worker/LotteryScheduler.java src/test/java/com/canagent/worker/LotterySchedulerTest.java
git commit -m "feat(lottery): 주간 구매·당첨확인 스케줄러(Asia/Seoul)"
```

---

### Task 10: prod DB 마이그레이션 SQL

`ddl-auto: update`가 로컬/테스트 테이블을 자동 생성하지만, prod(PostgreSQL)용 명시 SQL을 제공한다(기존 `scripts/db/` 관례).

**Files:**
- Create: `scripts/db/lottery-migration.sql`

- [ ] **Step 1: 마이그레이션 SQL 작성**

`scripts/db/lottery-migration.sql`:

```sql
-- 복권 자동구매: 티켓 기록 테이블 (PostgreSQL)
-- 로컬/테스트는 JPA ddl-auto:update가 자동 생성. prod 적용은 배포 게이트(사용자 승인).
CREATE TABLE IF NOT EXISTS lottery_ticket (
    id              BIGSERIAL PRIMARY KEY,
    game_type       VARCHAR(16)  NOT NULL,   -- LOTTO645 / WIN720
    round_no        INTEGER      NOT NULL,   -- 회차
    numbers         VARCHAR(64)  NOT NULL,   -- 로또 "3,7,12,25,33,41" / 연금 "조:6자리"
    amount          INTEGER      NOT NULL,
    purchased_at    TIMESTAMP    NOT NULL,
    result_checked  BOOLEAN      NOT NULL DEFAULT FALSE,
    rank            INTEGER,                 -- null=미확인, 0=미당첨, 1..=등수(연금 보너스=8)
    prize_label     VARCHAR(64),
    winner          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_lottery_game_checked   ON lottery_ticket (game_type, result_checked);
CREATE INDEX IF NOT EXISTS idx_lottery_game_purchased ON lottery_ticket (game_type, purchased_at);
```

- [ ] **Step 2: 컬럼 정합 확인 (엔티티 대조)**

`LotteryTicket` 필드와 SQL 컬럼이 일치하는지 육안 대조: `game_type, round_no, numbers, amount, purchased_at, result_checked, rank, prize_label, winner`. 불일치 없어야 함.

- [ ] **Step 3: 커밋**

```bash
git add scripts/db/lottery-migration.sql
git commit -m "chore(lottery): prod DB 마이그레이션 SQL"
```

---

### Task 11: 파이썬 사이드카 (구매 실행)

dhlottery 로그인·구매·잔액조회를 수행하고 JSON 계약(Global Constraints)을 stdout에 출력한다. 로또·잔액은 검증된 `dhapi`, 연금은 Playwright로 처리한다. 사이트 상호작용은 자동테스트가 어렵고 `--dry-run` 스모크로 검증한다.

**Files:**
- Create: `sidecar/lottery/buy.py`
- Create: `sidecar/lottery/requirements.txt`
- Create: `sidecar/lottery/.env.example`
- Create: `sidecar/lottery/README.md`
- Modify: `.gitignore` (사이드카 `.env` 제외)

**Interfaces:**
- Produces: CLI `buy.py {buy|balance} [--games LOTTO645,WIN720] [--dry-run]` → stdout 마지막 줄에 Global Constraints의 JSON.

- [ ] **Step 1: requirements.txt 작성**

`sidecar/lottery/requirements.txt`:

```
dhapi>=1.3.0
playwright>=1.40.0
python-dotenv>=1.0.0
```

- [ ] **Step 2: .env.example 작성**

`sidecar/lottery/.env.example`:

```
# 동행복권 계정 (이 파일을 .env로 복사해 채운다. .env는 커밋 금지)
DH_USER=your_dhlottery_id
DH_PASSWORD=your_dhlottery_password
```

- [ ] **Step 3: .gitignore에 사이드카 .env 추가**

`.gitignore`에 아래 줄 추가:

```
sidecar/lottery/.env
```

- [ ] **Step 4: buy.py 작성**

`sidecar/lottery/buy.py`:

```python
#!/usr/bin/env python3
"""동행복권 구매 사이드카. stdout 마지막 줄에 JSON 계약을 출력한다.

계약: {"ok": bool, "balanceAfter": int, "tickets": [...], "errors": [...]}
  tickets[].numbers: 로또 "3,7,12,25,33,41" / 연금 "조:6자리" 예 "3:123456"
로그는 stderr로만 보낸다(stdout은 JSON 전용).
"""
import argparse
import json
import os
import sys

from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))
DH_USER = os.environ.get("DH_USER")
DH_PASSWORD = os.environ.get("DH_PASSWORD")


def log(msg):
    print(msg, file=sys.stderr)


def _lotto_client():
    """dhapi 로그인 클라이언트 반환. dhapi 버전에 맞춰 조정한다."""
    from dhapi.router.dhlottery_client import DhlotteryClient  # dhapi 내부 클라이언트
    client = DhlotteryClient()
    client.login(DH_USER, DH_PASSWORD)
    return client


def get_balance(client):
    """예치금 조회. dhapi의 잔액 조회 API에 맞춰 반환(int 원)."""
    return int(client.get_balance())


def buy_lotto(client, dry_run):
    if dry_run:
        return None
    # 자동 1게임 구매. dhapi buy_lotto645 반환에서 회차·번호를 정규화한다.
    result = client.buy_lotto645(count=1, mode="auto")
    round_no = int(result["round"])
    nums = sorted(int(x) for x in result["numbers"][0])   # 첫 게임 6자리
    return {"gameType": "LOTTO645", "roundNo": round_no,
            "numbers": ",".join(str(n) for n in nums), "amount": 1000}


def buy_win720(dry_run):
    """연금복권720+ 자동 1조 구매(Playwright). 선택자는 라이브에서 codegen으로 확정한다."""
    if dry_run:
        return None
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        # 1) 로그인 → 2) 연금복권720+ 게임 페이지 → 3) 자동선택 → 4) 구매확정
        # 아래 선택자는 `playwright codegen https://dhlottery.co.kr` 로 확정 후 교체한다.
        round_no, jo, digits = _win720_interaction(page)
        browser.close()
    return {"gameType": "WIN720", "roundNo": int(round_no),
            "numbers": f"{jo}:{digits}", "amount": 1000}


def _win720_interaction(page):
    """실제 DOM 조작. techinpark/lottery-bot의 win720 흐름을 참고해 채운다.
    반환: (round_no, jo, digits) — digits는 6자리 문자열."""
    raise NotImplementedError("Playwright 선택자를 라이브 codegen으로 확정 후 구현")


def cmd_balance():
    client = _lotto_client()
    return {"ok": True, "balanceAfter": get_balance(client), "tickets": [], "errors": []}


def cmd_buy(games, dry_run):
    tickets, errors = [], []
    client = _lotto_client()

    if "LOTTO645" in games:
        try:
            t = buy_lotto(client, dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "LOTTO645", "reason": str(e)})

    if "WIN720" in games:
        try:
            t = buy_win720(dry_run)
            if t:
                tickets.append(t)
        except Exception as e:
            errors.append({"gameType": "WIN720", "reason": str(e)})

    balance = get_balance(client)
    return {"ok": len(errors) == 0, "balanceAfter": balance, "tickets": tickets, "errors": errors}


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    buy_p = sub.add_parser("buy")
    buy_p.add_argument("--games", default="LOTTO645,WIN720")
    buy_p.add_argument("--dry-run", action="store_true")
    sub.add_parser("balance")
    args = parser.parse_args()

    if not DH_USER or not DH_PASSWORD:
        print(json.dumps({"ok": False, "balanceAfter": 0, "tickets": [],
                          "errors": [{"gameType": "ALL", "reason": "크리덴셜 미설정(.env)"}]}))
        return

    try:
        if args.cmd == "balance":
            out = cmd_balance()
        else:
            games = [g.strip() for g in args.games.split(",") if g.strip()]
            out = cmd_buy(games, args.dry_run)
    except Exception as e:
        out = {"ok": False, "balanceAfter": 0, "tickets": [],
               "errors": [{"gameType": "ALL", "reason": str(e)}]}

    print(json.dumps(out, ensure_ascii=False))   # stdout 마지막 줄 = JSON


if __name__ == "__main__":
    main()
```

> **구현 주의:** `_lotto_client`/`buy_lotto`/`get_balance`는 설치된 `dhapi` 실제 버전의 API 시그니처에 맞춰 조정한다(`pip show dhapi`, 소스 확인). `_win720_interaction`은 `playwright codegen`으로 실제 선택자를 확정하거나 `techinpark/lottery-bot`의 win720 모듈을 이식한다. JSON 계약(키·numbers 정규화 형식)은 변경 금지 — Java 파싱과 결합된다.

- [ ] **Step 5: README 작성**

`sidecar/lottery/README.md`:

```markdown
# 복권 구매 사이드카

Java(can-agent)가 subprocess로 호출한다. stdout 마지막 줄에 JSON을 출력한다.

## 설치
    python3 -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    playwright install chromium
    cp .env.example .env    # DH_USER / DH_PASSWORD 입력

## 사용
    python3 buy.py balance                         # 예치금만
    python3 buy.py buy --games LOTTO645,WIN720 --dry-run   # 구매 없이 로그인·잔액
    python3 buy.py buy --games LOTTO645,WIN720             # 실구매

## Java 연동
application.yml:
    lottery.sidecar-command: "python3,/절대경로/sidecar/lottery/buy.py"
크리덴셜은 이 디렉터리 .env 에만 둔다(커밋 금지). Java는 크리덴셜을 다루지 않는다.
```

- [ ] **Step 6: dry-run 스모크 검증**

로컬에 파이썬 환경을 구성하고 실행:
```bash
cd sidecar/lottery && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && cp .env.example .env
# .env에 실계정 입력 후:
python3 buy.py balance
```
Expected: stdout 마지막 줄이 `{"ok": true, "balanceAfter": <정수>, "tickets": [], "errors": []}` 형태의 유효 JSON. 로그인 실패 시 `ok:false`와 사유가 담긴다. (실구매는 사용자 승인 전까지 하지 않는다.)

- [ ] **Step 7: 커밋**

```bash
git add sidecar/lottery/ .gitignore
git commit -m "feat(lottery): 파이썬 구매 사이드카(dhapi 로또·Playwright 연금)"
```

---

### Task 12: 전체 빌드·통합 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew test`
Expected: 신규 복권 테스트 전부 PASS + 기존 테스트(약 219개) 회귀 없음.

- [ ] **Step 2: 애플리케이션 컨텍스트 기동 확인(기능 비활성 기본값)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `lottery.enabled=false`(기본)이라 `LotteryScheduler` 빈은 등록되지 않고, 나머지 복권 빈은 정상 로드된다.

- [ ] **Step 3: 배포 전 체크리스트 확인(문서)**

아래를 사람이 확인(코드 아님):
- [ ] prod 서버에 python venv + requirements + `playwright install chromium` (+ 필요 시 tesseract) 설치
- [ ] `scripts/db/lottery-migration.sql` prod 적용 (또는 ddl-auto 확인)
- [ ] `sidecar/lottery/.env`에 실계정, `lottery.sidecar-command` 절대경로 설정
- [ ] `--dry-run`으로 로그인·잔액 검증 성공
- [ ] `notification.telegram.enabled=true` + 봇토큰/챗ID 설정
- [ ] **사용자 승인 후** `lottery.dry-run=false` → `lottery.enabled=true` 전환·배포

- [ ] **Step 4: 커밋(있으면)**

변경이 있으면 커밋. 없으면 스킵.

---

## 자기 검토 (작성자 체크리스트 결과)

**1. 스펙 커버리지:**
- 로또·연금 매주 자동구매 → Task 4(오케스트레이션)·Task 11(사이드카) ✅
- 자동(랜덤) 번호 → Task 11 `mode="auto"`/연금 자동 ✅
- 화요일 오전 구매 → Task 9 `buy-cron 0 0 9 * * TUE` ✅
- 당첨 알림(연금 목·로또 토) → Task 5/6/7/8 + Task 9 크론 ✅
- 예치금 <3,000원 알림 → Task 4 balanceThreshold ✅
- 텔레그램 발송(기존 인프라 재사용) → Task 3 sendText ✅
- 멱등성(중복구매 방지) → Task 4 `existsByGameTypeAndPurchasedAtAfter` ✅
- 크리덴셜 경계(사이드카 .env) → Task 11 ✅
- 안전 게이트(기본 false·dry-run) → Task 2 config + Task 9 조건부 빈 ✅
- DB 저장·마이그레이션 → Task 1·Task 10 ✅
- 테스트(Fake 포트·픽스처 등수) → Task 4·7 ✅

**2. 플레이스홀더 스캔:** Java 태스크는 완전한 코드·테스트 포함. 사이트 의존 2곳(연금 결과 파서 정규식, 연금 구매 Playwright 선택자)은 "라이브 캡처/codegen 후 조정"을 **명시적 실행 단계**로 지정 — 자동테스트 불가한 스크래핑/브라우저 영역의 정상적 처리. TBD/추후구현 없음.

**3. 타입 정합성:** `LotterySidecarPort.purchaseWeekly(List<GameType>)`·`getBalance()`, `SidecarResult(ok,balanceAfter,tickets,errors)`, `LotteryTicket.applyResult(Integer,String)`, `numbers` 정규화 형식("csv"/"조:6자리"), rank 코드(0 미당첨·8 보너스)가 Task 1·2·4·7·8·11 전반에서 일관됨. `NotificationService.sendText(String)`이 Task 3에서 정의되고 Task 4·8에서 소비됨.
