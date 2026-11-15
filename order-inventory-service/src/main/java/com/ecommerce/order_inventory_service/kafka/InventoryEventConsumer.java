package com.ecommerce.order_inventory_service.kafka;

import com.ecommerce.order_inventory_service.config.KafkaConfig;
import com.ecommerce.order_inventory_service.event.OrderCreatedEvent;
import com.ecommerce.order_inventory_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventConsumer {

    private final OrderService orderService;

    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_TOPIC, groupId = "inventory-service-group")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Consumed OrderCreatedEvent from Kafka for Order: {}", event.getOrderNumber());
        orderService.processInventoryReservation(event);
    }

    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_DLT, groupId = "inventory-dlt-group")
    public void handleDeadLetterEvent(OrderCreatedEvent event) {
        log.error("ALERT: Message routed to Dead-Letter Queue (DLT) for Order: {}", event.getOrderNumber());
    }
}