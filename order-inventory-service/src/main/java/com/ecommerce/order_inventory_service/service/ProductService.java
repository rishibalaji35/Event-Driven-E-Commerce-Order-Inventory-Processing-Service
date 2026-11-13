package com.ecommerce.order_inventory_service.service;

import com.ecommerce.order_inventory_service.dto.ProductResponse;
import com.ecommerce.order_inventory_service.entity.Product;
import com.ecommerce.order_inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.order_inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    @Cacheable(value = "products", key = "#sku")
    @Transactional(readOnly = true)
    public ProductResponse getProductBySku(String sku) {
        log.info("Cache MISS - Fetching product from MySQL for SKU: {}", sku);
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @CachePut(value = "products", key = "#sku")
    @Transactional
    public ProductResponse updateStock(String sku, Integer newStock) {
        log.info("Updating stock in MySQL and refreshing Redis cache for SKU: {}", sku);
        Product product = productRepository.findBySkuWithLock(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        product.setAvailableStock(newStock);
        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @CacheEvict(value = "products", key = "#sku")
    public void evictProductCache(String sku) {
        log.info("Evicted Redis cache for SKU: {}", sku);
    }

    private ProductResponse mapToResponse(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .availableStock(p.getAvailableStock())
                .build();
    }
}