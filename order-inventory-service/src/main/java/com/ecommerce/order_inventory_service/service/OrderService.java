package com.ecommerce.order_inventory_service.service;

import com.ecommerce.order_inventory_service.dto.*;
import com.ecommerce.order_inventory_service.entity.*;
import com.ecommerce.order_inventory_service.exception.InsufficientStockException;
import com.ecommerce.order_inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.order_inventory_service.repository.InventoryAuditRepository;
import com.ecommerce.order_inventory_service.repository.OrderRepository;
import com.ecommerce.order_inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryAuditRepository auditRepository;
    private final ProductService productService;

    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        String orderNumber = UUID.randomUUID().toString();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .customerId(request.getCustomerId())
                .status(OrderStatus.CONFIRMED) // Will become PENDING in Day 4 when Kafka async flow is added
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Sort items by SKU to prevent database deadlocks during multi-item concurrent orders
        List<OrderItemRequest> sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(OrderItemRequest::getSku))
                .toList();

        for (OrderItemRequest itemReq : sortedItems) {
            // Acquires MySQL row-level write lock (SELECT ... FOR UPDATE)
            Product product = productRepository.findBySkuWithLock(itemReq.getSku())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getSku()));

            if (product.getAvailableStock() < itemReq.getQuantity()) {
                throw new InsufficientStockException(
                        String.format("Insufficient stock for %s. Requested: %d, Available: %d",
                                product.getSku(), itemReq.getQuantity(), product.getAvailableStock())
                );
            }

            // Deduct available stock and move to reserved stock
            product.setAvailableStock(product.getAvailableStock() - itemReq.getQuantity());
            product.setReservedStock(product.getReservedStock() + itemReq.getQuantity());
            productRepository.save(product);

            // Evict Redis cache so subsequent GET requests fetch fresh stock
            productService.evictProductCache(product.getSku());

            // Record audit trail
            InventoryAudit audit = InventoryAudit.builder()
                    .product(product)
                    .orderNumber(orderNumber)
                    .changeType("RESERVED")
                    .quantityChanged(-itemReq.getQuantity())
                    .build();
            auditRepository.save(audit);

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            totalAmount = totalAmount.add(lineTotal);

            OrderItem orderItem = OrderItem.builder()
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(product.getPrice())
                    .build();
            order.addItem(orderItem);
        }

        order.setTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);
        log.info("Order {} placed successfully for customer {}", orderNumber, request.getCustomerId());

        return mapToResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderByNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderNumber));
        return mapToResponse(order);
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderResponse.OrderItemSummary> itemSummaries = order.getItems().stream()
                .map(item -> OrderResponse.OrderItemSummary.builder()
                        .sku(item.getProduct().getSku())
                        .productName(item.getProduct().getName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .toList();

        return OrderResponse.builder()
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .items(itemSummaries)
                .createdAt(order.getCreatedAt())
                .build();
    }
}