package com.ecommerce.order_inventory_service.repository;

import com.ecommerce.order_inventory_service.entity.InventoryAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryAuditRepository extends JpaRepository<InventoryAudit, Long> {
}