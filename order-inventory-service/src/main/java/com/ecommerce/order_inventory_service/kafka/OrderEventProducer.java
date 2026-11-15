package com.ecommerce.order_inventory_service.kafka;

import com.ecommerce.order_inventory_service.config.KafkaConfig;
import com.ecommerce.order_inventory_service.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent to Kafka for Order: {}", event.getOrderNumber());
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, event.getOrderNumber(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Event published successfully for Order: {} [Partition: {}, Offset: {}]",
                                event.getOrderNumber(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to publish OrderCreatedEvent for Order: {}", event.getOrderNumber(), ex);
                    }
                });
    }
}