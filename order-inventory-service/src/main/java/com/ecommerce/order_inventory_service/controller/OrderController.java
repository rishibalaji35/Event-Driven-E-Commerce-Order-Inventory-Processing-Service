package com.ecommerce.order_inventory_service.controller;

import com.ecommerce.order_inventory_service.dto.OrderRequest;
import com.ecommerce.order_inventory_service.dto.OrderResponse;
import com.ecommerce.order_inventory_service.service.IdempotencyService;
import com.ecommerce.order_inventory_service.service.OrderService;
import com.ecommerce.order_inventory_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {

        OrderResponse response = idempotencyService.executeIdempotent(
                idempotencyKey,
                () -> orderService.placeOrderAsync(request)
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderNumber) {
        return ResponseEntity.ok(orderService.getOrderByNumber(orderNumber));
    }

    @PostMapping("/simulate-payment-outage")
    public ResponseEntity togglePaymentOutage(@RequestParam boolean enabled) {
        paymentService.setSimulateOutage(enabled);
        return ResponseEntity.ok(Map.of(
                "paymentOutageSimulated", paymentService.isOutageSimulated()
        ));
    }
}