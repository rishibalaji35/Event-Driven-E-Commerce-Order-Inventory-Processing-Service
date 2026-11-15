package com.ecommerce.order_inventory_service.service;

import com.ecommerce.order_inventory_service.dto.*;
import com.ecommerce.order_inventory_service.entity.*;
import com.ecommerce.order_inventory_service.event.OrderCreatedEvent;
import com.ecommerce.order_inventory_service.exception.InsufficientStockException;
import com.ecommerce.order_inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.order_inventory_service.kafka.OrderEventProducer;
import com.ecommerce.order_inventory_service.repository.InventoryAuditRepository;
import com.ecommerce.order_inventory_service.repository.OrderRepository;
import com.ecommerce.order_inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final OrderEventProducer orderEventProducer;

    /**
     * Day 4: Asynchronous Event-Driven Order Creation
     */
    @Transactional
    public OrderResponse placeOrderAsync(OrderRequest request) {
        String orderNumber = UUID.randomUUID().toString();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .customerId(request.getCustomerId())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequest itemReq : request.getItems()) {
            Product product = productRepository.findBySku(itemReq.getSku())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getSku()));

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

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderNumber(savedOrder.getOrderNumber())
                .customerId(savedOrder.getCustomerId())
                .totalAmount(savedOrder.getTotalAmount())
                .items(request.getItems().stream()
                        .map(i -> new OrderCreatedEvent.OrderItemPayload(i.getSku(), i.getQuantity()))
                        .toList())
                .build();

        // Critical: Publish to Kafka ONLY AFTER MySQL commits the PENDING order transaction
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderEventProducer.publishOrderCreated(event);
            }
        });

        log.info("Order {} accepted with status PENDING", orderNumber);
        return mapToResponse(savedOrder);
    }

    /**
     * Executed asynchronously by Kafka Consumer to reserve stock with pessimistic locking
     */
    @Transactional
    public void processInventoryReservation(OrderCreatedEvent event) {
        Order order = orderRepository.findByOrderNumber(event.getOrderNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + event.getOrderNumber()));

        // Idempotency guard: skip if already processed
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Order {} already processed with status {}. Skipping duplicate Kafka event.",
                    order.getOrderNumber(), order.getStatus());
            return;
        }

        try {
            List<OrderCreatedEvent.OrderItemPayload> sortedItems = event.getItems().stream()
                    .sorted(Comparator.comparing(OrderCreatedEvent.OrderItemPayload::getSku))
                    .toList();

            for (OrderCreatedEvent.OrderItemPayload item : sortedItems) {
                Product product = productRepository.findBySkuWithLock(item.getSku())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.getSku()));

                if (product.getAvailableStock() < item.getQuantity()) {
                    throw new InsufficientStockException(
                            String.format("Insufficient stock for %s. Requested: %d, Available: %d",
                                    product.getSku(), item.getQuantity(), product.getAvailableStock())
                    );
                }

                product.setAvailableStock(product.getAvailableStock() - item.getQuantity());
                product.setReservedStock(product.getReservedStock() + item.getQuantity());
                productRepository.save(product);

                productService.evictProductCache(product.getSku());

                auditRepository.save(InventoryAudit.builder()
                        .product(product)
                        .orderNumber(order.getOrderNumber())
                        .changeType("RESERVED")
                        .quantityChanged(-item.getQuantity())
                        .build());
            }

            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            log.info("Order {} transitioned to CONFIRMED after inventory reservation.", order.getOrderNumber());

        } catch (InsufficientStockException ex) {
            // Business failure: mark order FAILED cleanly without sending to Dead-Letter Queue
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(ex.getMessage());
            orderRepository.save(order);
            log.warn("Order {} marked FAILED: {}", order.getOrderNumber(), ex.getMessage());
        }
    }

    /**
     * Day 3: Synchronous Order Placement (kept for direct concurrency testing)
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        String orderNumber = UUID.randomUUID().toString();

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .customerId(request.getCustomerId())
                .status(OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        List<OrderItemRequest> sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(OrderItemRequest::getSku))
                .toList();

        for (OrderItemRequest itemReq : sortedItems) {
            Product product = productRepository.findBySkuWithLock(itemReq.getSku())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + itemReq.getSku()));

            if (product.getAvailableStock() < itemReq.getQuantity()) {
                throw new InsufficientStockException(
                        String.format("Insufficient stock for %s. Requested: %d, Available: %d",
                                product.getSku(), itemReq.getQuantity(), product.getAvailableStock())
                );
            }

            product.setAvailableStock(product.getAvailableStock() - itemReq.getQuantity());
            product.setReservedStock(product.getReservedStock() + itemReq.getQuantity());
            productRepository.save(product);

            productService.evictProductCache(product.getSku());

            auditRepository.save(InventoryAudit.builder()
                    .product(product)
                    .orderNumber(orderNumber)
                    .changeType("RESERVED")
                    .quantityChanged(-itemReq.getQuantity())
                    .build());

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            totalAmount = totalAmount.add(lineTotal);

            order.addItem(OrderItem.builder()
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(product.getPrice())
                    .build());
        }

        order.setTotalAmount(totalAmount);
        return mapToResponse(orderRepository.save(order));
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