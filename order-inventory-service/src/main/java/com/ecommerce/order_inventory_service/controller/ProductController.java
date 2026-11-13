package com.ecommerce.order_inventory_service.controller;

import com.ecommerce.order_inventory_service.dto.ProductResponse;
import com.ecommerce.order_inventory_service.dto.StockUpdateRequest;
import com.ecommerce.order_inventory_service.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/{sku}")
    public ResponseEntity<ProductResponse> getProductBySku(@PathVariable String sku) {
        return ResponseEntity.ok(productService.getProductBySku(sku));
    }

    @PatchMapping("/{sku}/stock")
    public ResponseEntity<ProductResponse> updateStock(
            @PathVariable String sku,
            @Valid @RequestBody StockUpdateRequest request) {
        return ResponseEntity.ok(productService.updateStock(sku, request.getAvailableStock()));
    }
}