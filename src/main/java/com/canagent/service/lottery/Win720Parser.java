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
    // <span class="num">숫자</span> 패턴
    private static final Pattern NUM = Pattern.compile("class=\"num\"[^>]*>\\s*(\\d)\\s*<");

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
        // 해당 블록만 슬라이스 — 다음 <div 시작 전까지만 탐색해 인접 블록 유출을 방지한다.
        int end = html.indexOf("<div", start + 1);
        String region = end > 0 ? html.substring(start, end) : html.substring(start);
        Matcher m = NUM.matcher(region);
        StringBuilder sb = new StringBuilder();
        while (m.find() && sb.length() < 6) {
            sb.append(m.group(1));
        }
        return sb.toString();
    }
}
