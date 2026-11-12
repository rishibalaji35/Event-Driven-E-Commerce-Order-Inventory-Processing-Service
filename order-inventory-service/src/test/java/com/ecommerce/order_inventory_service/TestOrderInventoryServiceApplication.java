package com.ecommerce.order_inventory_service;

import org.springframework.boot.SpringApplication;

public class TestOrderInventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(OrderInventoryServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
