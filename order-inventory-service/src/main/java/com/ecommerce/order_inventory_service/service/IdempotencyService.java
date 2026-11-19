package com.ecommerce.order_inventory_service.service;

import com.ecommerce.order_inventory_service.dto.OrderResponse;
import com.ecommerce.order_inventory_service.exception.DuplicateRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "idempotency:";
    private static final String PROCESSING_MARKER = "PROCESSING";
    private static final Duration KEY_TTL = Duration.ofHours(24);

    public OrderResponse executeIdempotent(String idempotencyKey, Supplier<OrderResponse> action) {
        // If client did not send an Idempotency-Key header, execute normally
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }

        String redisKey = KEY_PREFIX + idempotencyKey;

        // Atomic SETNX: returns true ONLY for the very first request with this key
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, PROCESSING_MARKER, Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(acquired)) {
            String existingValue = redisTemplate.opsForValue().get(redisKey);

            if (PROCESSING_MARKER.equals(existingValue)) {
                throw new DuplicateRequestException(
                        "An order with Idempotency-Key '" + idempotencyKey + "' is currently being processed."
                );
            }

            if (existingValue != null) {
                log.info("Idempotency HIT for key: {}. Returning cached OrderResponse.", idempotencyKey);
                return objectMapper.readValue(existingValue, OrderResponse.class);
            }
        }

        try {
            OrderResponse response = action.get();
            String serializedResponse = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, serializedResponse, KEY_TTL);
            return response;
        } catch (Exception ex) {
            // Release the lock if order creation fails so the user can retry
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }
}