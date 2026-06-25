package com.canagent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TradeAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TradeAuthInterceptor.class);

    @Value("${trading.secret:}")
    private String tradeSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (tradeSecret == null || tradeSecret.isBlank()) {
            return true;
        }

        String providedSecret = request.getHeader("X-Trade-Secret");
        if (tradeSecret.equals(providedSecret)) {
            return true;
        }

        log.warn("Unauthorized trade request: {}", request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
        return false;
    }
}
