package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.client.CatalogClient;
import com.hotel.hotelbookingsystem.client.InventoryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableFeignClients(clients = {CatalogClient.class, InventoryClient.class})
@EnableDiscoveryClient
@EnableScheduling
@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
