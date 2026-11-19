package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.client.OrderSpendingClient;
import com.hotel.hotelbookingsystem.client.PetSpendingClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableFeignClients(clients = {OrderSpendingClient.class, PetSpendingClient.class})
@EnableDiscoveryClient
@SpringBootApplication
public class EngagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngagementApplication.class, args);
    }
}
