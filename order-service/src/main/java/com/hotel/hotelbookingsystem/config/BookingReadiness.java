package com.hotel.hotelbookingsystem.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keep traffic out until the schema and legacy stock migration have committed. */
@Component
public class BookingReadiness extends OncePerRequestFilter implements ApplicationListener<ApplicationReadyEvent> {
    private volatile boolean ready;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) { ready = true; }

    public boolean isReady() { return ready; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        if (!ready) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"订单服务正在初始化，请稍后重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
