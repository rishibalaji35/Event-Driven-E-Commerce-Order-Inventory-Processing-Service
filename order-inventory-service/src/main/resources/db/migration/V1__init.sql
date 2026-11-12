CREATE TABLE products (
                          id BIGINT AUTO_INCREMENT PRIMARY KEY,
                          sku VARCHAR(64) NOT NULL UNIQUE,
                          name VARCHAR(255) NOT NULL,
                          description TEXT,
                          price DECIMAL(10, 2) NOT NULL,
                          available_stock INT NOT NULL,
                          reserved_stock INT NOT NULL DEFAULT 0,
                          version BIGINT NOT NULL DEFAULT 0,
                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                          INDEX idx_products_sku (sku)
) ENGINE=InnoDB;

CREATE TABLE orders (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        order_number VARCHAR(36) NOT NULL UNIQUE,
                        customer_id VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        total_amount DECIMAL(10, 2) NOT NULL,
                        failure_reason VARCHAR(255),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_orders_number (order_number),
                        INDEX idx_orders_customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE order_items (
                             id BIGINT AUTO_INCREMENT PRIMARY KEY,
                             order_id BIGINT NOT NULL,
                             product_id BIGINT NOT NULL,
                             quantity INT NOT NULL,
                             unit_price DECIMAL(10, 2) NOT NULL,
                             CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
                             CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB;

CREATE TABLE inventory_audit (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 product_id BIGINT NOT NULL,
                                 order_number VARCHAR(36) NOT NULL,
                                 change_type VARCHAR(32) NOT NULL,
                                 quantity_changed INT NOT NULL,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_audit_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB;

-- Seed initial catalog data for testing
INSERT INTO products (sku, name, description, price, available_stock, reserved_stock) VALUES
                                                                                          ('IPHONE-16-PRO', 'iPhone 16 Pro 256GB', 'Titanium Black flagship smartphone', 1199.00, 10, 0),
                                                                                          ('MACBOOK-M3-AIR', 'MacBook Air M3 15-inch', '16GB RAM, 512GB SSD', 1499.00, 5, 0),
                                                                                          ('SONY-WH1000XM5', 'Sony WH-1000XM5 Headphones', 'Wireless noise-canceling headphones', 349.99, 25, 0);