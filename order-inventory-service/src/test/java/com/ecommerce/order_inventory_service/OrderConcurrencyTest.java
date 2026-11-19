package com.ecommerce.order_inventory_service;

import com.ecommerce.order_inventory_service.dto.OrderItemRequest;
import com.ecommerce.order_inventory_service.dto.OrderRequest;
import com.ecommerce.order_inventory_service.entity.Product;
import com.ecommerce.order_inventory_service.exception.InsufficientStockException;
import com.ecommerce.order_inventory_service.repository.ProductRepository;
import com.ecommerce.order_inventory_service.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class OrderConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    private static final String TEST_SKU = "FLASH-SALE-ITEM";

    @BeforeEach
    void setup() {
        productRepository.findBySku(TEST_SKU).ifPresentOrElse(
                product -> {
                    product.setAvailableStock(5);
                    product.setReservedStock(0);
                    productRepository.save(product);
                },
                () -> productRepository.save(Product.builder()
                        .sku(TEST_SKU)
                        .name("Flash Sale GPU")
                        .description("Limited edition graphics card")
                        .price(new BigDecimal("999.00"))
                        .availableStock(5)
                        .reservedStock(0)
                        .build())
        );
    }

    @Test
    void shouldPreventOversellingWhen20ThreadsOrderSimultaneously() throws InterruptedException {
        int totalThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger failedOrders = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            final String customerId = "CUST-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Hold all 20 threads so they fire at the exact same instant
                    OrderRequest request = new OrderRequest(
                            customerId,
                            List.of(new OrderItemRequest(TEST_SKU, 1))
                    );
                    orderService.placeOrder(request);
                    successfulOrders.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failedOrders.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all 20 threads simultaneously
        doneLatch.await();      // Wait for all 20 to finish
        executor.shutdown();

        Product updatedProduct = productRepository.findBySku(TEST_SKU).orElseThrow();

        System.out.println("Successful Orders: " + successfulOrders.get());
        System.out.println("Rejected Orders (Out of Stock): " + failedOrders.get());
        System.out.println("Remaining Available Stock: " + updatedProduct.getAvailableStock());

        assertEquals(5, successfulOrders.get(), "Exactly 5 orders should succeed");
        assertEquals(15, failedOrders.get(), "Exactly 15 orders should be rejected due to insufficient stock");
        assertEquals(0, updatedProduct.getAvailableStock(), "Available stock must be 0, never negative");
        assertEquals(5, updatedProduct.getReservedStock(), "Reserved stock must equal 5");
    }
}