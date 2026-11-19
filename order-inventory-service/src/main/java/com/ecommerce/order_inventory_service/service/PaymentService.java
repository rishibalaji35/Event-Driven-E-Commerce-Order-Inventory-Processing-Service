package com.ecommerce.order_inventory_service.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class PaymentService {

    private final AtomicBoolean simulateOutage = new AtomicBoolean(false);

    public void setSimulateOutage(boolean outage) {
        this.simulateOutage.set(outage);
        log.warn("Payment Gateway Outage Simulation set to: {}", outage);
    }

    public boolean isOutageSimulated() {
        return simulateOutage.get();
    }

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public boolean processPayment(String orderNumber, BigDecimal amount) {
        log.info("Attempting payment charge of ${} for Order: {}", amount, orderNumber);

        if (simulateOutage.get()) {
            log.error("Payment gateway timeout/503 error for Order: {}", orderNumber);
            throw new RuntimeException("External Payment Gateway Unavailable");
        }

        log.info("Payment approved for Order: {}", orderNumber);
        return true;
    }

    @SuppressWarnings("unused")
    public boolean paymentFallback(String orderNumber, BigDecimal amount, Throwable throwable) {
        log.error("FALLBACK TRIGGERED for Order: {} (Amount: ${}). Reason: {}",
                orderNumber, amount, throwable.getMessage());
        return false;
    }
}