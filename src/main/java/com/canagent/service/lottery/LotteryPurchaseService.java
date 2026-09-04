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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class LotteryPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(LotteryPurchaseService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LotterySidecarPort port;
    private final LotteryTicketRepository repository;
    private final NotificationServiceRouter router;
    private final LotteryConfig config;
    private final Clock clock;

    // 주간 재시도(buy-retry-cron)로 같은 실패 알림이 반복되지 않도록 주 단위로 중복을 억제한다.
    private LocalDateTime notifiedWeekStart;
    private final Set<String> notifiedKeys = new HashSet<>();

    public LotteryPurchaseService(LotterySidecarPort port, LotteryTicketRepository repository,
                                  NotificationServiceRouter router, LotteryConfig config, Clock clock) {
        this.port = port;
        this.repository = repository;
        this.router = router;
        this.config = config;
        this.clock = clock;
    }

    /** 이번 주 미구매 게임만 구매한다. 재시도 크론이 반복 호출해도 안전한 멱등 연산. */
    public synchronized void buyWeekly() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDateTime weekStart = nowKst.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        resetNotificationsOnNewWeek(weekStart);

        // 설정된 게임(lottery.games)만 대상. 배포 시 LOTTERY_GAMES=LOTTO645로 연금 제외 가능.
        List<GameType> pending = config.getGames().stream()
                .filter(g -> !repository.existsByGameTypeAndPurchasedAtAfter(g, weekStart))
                .toList();

        if (pending.isEmpty()) {
            log.info("이번 주 복권 이미 구매됨(또는 대상 게임 없음) — 스킵");
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
            notifyOnce(failureKey(e),
                    "❌ 복권 구매 실패: " + e.gameType() + " — " + e.reason()
                            + "\n(판매마감 전까지 자동 재시도 — 동일 사유 반복 알림은 생략합니다)");
        }
        if (result.balanceAfter() < config.getBalanceThreshold()) {
            notifyOnce("BALANCE", "⚠️ 예치금 부족: 현재 " + result.balanceAfter() + "원 (임계 "
                    + config.getBalanceThreshold() + "원). 충전이 필요합니다.");
        }
    }

    /**
     * 재시도 창이 끝난 뒤 남은 미구매 게임을 최종 통보한다.
     * 실패 알림이 주 1회로 억제되므로, 결말(샀는지 못 샀는지)은 여기서 확실히 알린다.
     */
    public synchronized void reportUnpurchased() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDateTime weekStart = nowKst.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();

        List<GameType> missing = config.getGames().stream()
                .filter(g -> !repository.existsByGameTypeAndPurchasedAtAfter(g, weekStart))
                .toList();

        if (missing.isEmpty()) {
            log.info("이번 주 복권 구매 완료 — 최종 통보 없음");
            return;
        }
        log.warn("이번 주 복권 최종 미구매: {}", missing);
        router.sendText("🚨 이번 주 복권 구매 최종 실패: " + missing
                + "\n자동 재시도를 모두 소진했습니다. 수동 구매 또는 예치금 확인이 필요합니다.");
    }

    /** 주가 바뀌면 알림 중복 억제 상태를 초기화한다(새 주에는 같은 사유도 다시 알린다). */
    private void resetNotificationsOnNewWeek(LocalDateTime weekStart) {
        if (!weekStart.equals(notifiedWeekStart)) {
            notifiedWeekStart = weekStart;
            notifiedKeys.clear();
        }
    }

    /**
     * 실패 알림 억제 키. 사유 원문에는 페이지 본문 같은 가변 문구가 섞이므로
     * ':' 또는 '(' 앞의 고정 머리말만 사용해 같은 성격의 실패를 한 건으로 묶는다.
     */
    private String failureKey(SidecarError e) {
        String head = e.reason() == null ? "" : e.reason().split("[:(]", 2)[0].strip();
        if (head.length() > 40) head = head.substring(0, 40);
        return "ERR|" + e.gameType() + "|" + head;
    }

    /** 같은 주에 같은 키의 알림은 1회만 발송한다. */
    private void notifyOnce(String key, String message) {
        if (notifiedKeys.add(key)) {
            router.sendText(message);
        } else {
            log.info("복권 알림 중복 억제(이번 주 발송됨): {}", key);
        }
    }

    private String formatPurchase(SidecarResult result) {
        StringBuilder sb = new StringBuilder("🎫 복권 구매 완료\n");
        for (PurchasedTicket t : result.tickets()) {
            String name = switch (t.gameType()) {
                case LOTTO645 -> "로또6/45";
                case WIN720   -> "연금복권720+";
            };
            sb.append("• ").append(name).append(" ").append(t.roundNo()).append("회 [")
              .append(t.numbers()).append("] ").append(t.amount()).append("원\n");
        }
        sb.append("• 잔액: ").append(result.balanceAfter()).append("원");
        return sb.toString();
    }
}
