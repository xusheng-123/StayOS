package com.stayos.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTrackingFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RequestTrackingFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString();
        response.setHeader("X-Request-ID",id);MDC.put("requestId",id);
        try { chain.doFilter(request,response); }
        finally {
            boolean expectedSignedOut=response.getStatus()==401 && (request.getServletPath().equals("/api/auth/me")||request.getServletPath().equals("/api/auth/status"));
            if(response.getStatus()>=400 && !expectedSignedOut) {
                Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // No raw URL/query/body, exception message, credentials or guest data.
                log.warn("Request failed id={} method={} route={} status={}",id,request.getMethod(),pattern==null?"UNMAPPED":pattern,response.getStatus());
            }
            MDC.remove("requestId");
        }
    }
}
