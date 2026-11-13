package com.ecommerce.order_inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "order_number", nullable = false, length = 36)
    private String orderNumber;

    @Column(name = "change_type", nullable = false, length = 32)
    private String changeType; // e.g., RESERVED, RELEASED, RESTOCKED

    @Column(name = "quantity_changed", nullable = false)
    private Integer quantityChanged;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}