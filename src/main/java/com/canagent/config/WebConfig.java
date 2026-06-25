package com.canagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TradeAuthInterceptor tradeAuthInterceptor;

    public WebConfig(TradeAuthInterceptor tradeAuthInterceptor) {
        this.tradeAuthInterceptor = tradeAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tradeAuthInterceptor)
                .addPathPatterns("/trade/run")
                .excludePathPatterns("/", "/css/**", "/js/**");
    }
}
