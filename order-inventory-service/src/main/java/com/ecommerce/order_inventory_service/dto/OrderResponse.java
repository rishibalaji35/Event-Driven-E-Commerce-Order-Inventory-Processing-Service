package com.ecommerce.order_inventory_service.dto;

import com.ecommerce.order_inventory_service.entity.OrderStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {
    private String orderNumber;
    private String customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private List<OrderItemSummary> items;
    private LocalDateTime createdAt;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemSummary {
        private String sku;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}