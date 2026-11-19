package com.ecommerce.order_inventory_service;

import com.ecommerce.order_inventory_service.dto.OrderItemRequest;
import com.ecommerce.order_inventory_service.dto.OrderRequest;
import com.ecommerce.order_inventory_service.dto.OrderResponse;
import com.ecommerce.order_inventory_service.entity.OrderStatus;
import com.ecommerce.order_inventory_service.entity.Product;
import com.ecommerce.order_inventory_service.repository.OrderRepository;
import com.ecommerce.order_inventory_service.repository.ProductRepository;
import com.ecommerce.order_inventory_service.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class OrderPipelineIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentService paymentService;

    private static final String TEST_SKU = "TC-LAPTOP-PRO";

    @BeforeEach
    void setUp() {
        paymentService.setSimulateOutage(false);
        orderRepository.deleteAll();

        productRepository.findBySku(TEST_SKU).ifPresentOrElse(
                p -> {
                    p.setAvailableStock(10);
                    p.setReservedStock(0);
                    productRepository.save(p);
                },
                () -> productRepository.save(Product.builder()
                        .sku(TEST_SKU)
                        .name("Testcontainers Workstation")
                        .description("Isolated integration test product")
                        .price(new BigDecimal("1500.00"))
                        .availableStock(10)
                        .reservedStock(0)
                        .build())
        );
    }

    @Test
    void shouldProcessOrderEndToEndThroughKafkaAndConfirm() {
        OrderRequest request = new OrderRequest(
                "CUST-INT-1",
                List.of(new OrderItemRequest(TEST_SKU, 2))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<OrderRequest> entity = new HttpEntity<>(request, headers);

        // 1. Ingestion returns 202 Accepted immediately with PENDING status
        ResponseEntity<OrderResponse> postResponse = restTemplate.postForEntity(
                "/api/v1/orders", entity, OrderResponse.class);

        assertEquals(HttpStatus.ACCEPTED, postResponse.getStatusCode());
        assertNotNull(postResponse.getBody());
        assertEquals(OrderStatus.PENDING, postResponse.getBody().getStatus());
        String orderNumber = postResponse.getBody().getOrderNumber();

        // 2. Poll until Kafka consumer finishes reserving stock & charging payment
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ResponseEntity<OrderResponse> getResponse = restTemplate.getForEntity(
                    "/api/v1/orders/" + orderNumber, OrderResponse.class);
            assertEquals(HttpStatus.OK, getResponse.getStatusCode());
            assertNotNull(getResponse.getBody());
            assertEquals(OrderStatus.CONFIRMED, getResponse.getBody().getStatus());
        });

        // 3. Verify MySQL inventory updated accurately (10 - 2 = 8 available, 2 reserved)
        Product product = productRepository.findBySku(TEST_SKU).orElseThrow();
        assertEquals(8, product.getAvailableStock());
        assertEquals(2, product.getReservedStock());
    }

    @Test
    void shouldReturnSameOrderAndPreventDuplicateDeductionOnIdempotentRetry() {
        String idempotencyKey = "idem-retry-" + UUID.randomUUID();
        OrderRequest request = new OrderRequest(
                "CUST-INT-2",
                List.of(new OrderItemRequest(TEST_SKU, 3))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<OrderRequest> entity = new HttpEntity<>(request, headers);

        // Fire the exact same request twice
        ResponseEntity<OrderResponse> firstResponse = restTemplate.postForEntity(
                "/api/v1/orders", entity, OrderResponse.class);
        ResponseEntity<OrderResponse> secondResponse = restTemplate.postForEntity(
                "/api/v1/orders", entity, OrderResponse.class);

        assertEquals(HttpStatus.ACCEPTED, firstResponse.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, secondResponse.getStatusCode());
        assertEquals(firstResponse.getBody().getOrderNumber(), secondResponse.getBody().getOrderNumber());

        // Wait for async Kafka processing to complete
        String orderNumber = firstResponse.getBody().getOrderNumber();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OrderResponse order = restTemplate.getForObject("/api/v1/orders/" + orderNumber, OrderResponse.class);
            assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        });

        // Verify only 1 order exists and stock was deducted only ONCE (10 - 3 = 7)
        assertEquals(1, orderRepository.count());
        Product product = productRepository.findBySku(TEST_SKU).orElseThrow();
        assertEquals(7, product.getAvailableStock());
        assertEquals(3, product.getReservedStock());
    }

    @Test
    void shouldRollbackReservedStockWhenPaymentGatewayFails() {
        paymentService.setSimulateOutage(true);

        OrderRequest request = new OrderRequest(
                "CUST-INT-3",
                List.of(new OrderItemRequest(TEST_SKU, 4))
        );

        ResponseEntity<OrderResponse> postResponse = restTemplate.postForEntity(
                "/api/v1/orders", request, OrderResponse.class);
        String orderNumber = postResponse.getBody().getOrderNumber();

        // Wait for Kafka consumer to attempt payment, trigger fallback, and mark order FAILED
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OrderResponse order = restTemplate.getForObject("/api/v1/orders/" + orderNumber, OrderResponse.class);
            assertEquals(OrderStatus.FAILED, order.getStatus());
        });

        // Compensating transaction must have restored all 4 units back to availableStock
        Product product = productRepository.findBySku(TEST_SKU).orElseThrow();
        assertEquals(10, product.getAvailableStock());
        assertEquals(0, product.getReservedStock());
    }
}