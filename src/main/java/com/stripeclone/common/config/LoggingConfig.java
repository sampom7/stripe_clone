package com.stripeclone.common.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

@Configuration
@Slf4j
public class LoggingConfig {
    
    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> loggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new RequestLoggingFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(1);
        return registrationBean;
    }
    
    static class RequestLoggingFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, java.io.IOException {
        
        // Generate trace ID for request correlation
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }
        
        MDC.put("traceId", traceId);
        MDC.put("spanId", UUID.randomUUID().toString().substring(0, 8));
        
        try {
            long startTime = System.currentTimeMillis();
            filterChain.doFilter(request, response);
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("{} {} {} - {}ms", 
                request.getMethod(), 
                request.getRequestURI(), 
                response.getStatus(),
                duration);
        } finally {
            MDC.clear();
        }
    }
    }
}

