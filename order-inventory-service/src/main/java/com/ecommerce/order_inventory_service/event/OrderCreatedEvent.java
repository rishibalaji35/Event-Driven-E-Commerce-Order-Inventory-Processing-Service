package com.ecommerce.order_inventory_service.event;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderCreatedEvent {
    private String orderNumber;
    private String customerId;
    private BigDecimal totalAmount;
    private List<OrderItemPayload> items;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemPayload {
        private String sku;
        private Integer quantity;
    }
}