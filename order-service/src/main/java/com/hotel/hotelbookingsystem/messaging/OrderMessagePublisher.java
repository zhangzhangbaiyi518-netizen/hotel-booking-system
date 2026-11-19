package com.hotel.hotelbookingsystem.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderMessagePublisher {
    private final RabbitTemplate rabbit;
    private final OrderRabbitConfiguration topology;
    public OrderMessagePublisher(RabbitTemplate rabbit, OrderRabbitConfiguration topology) {
        this.rabbit = rabbit; this.topology = topology;
    }
    public void publish(OrderOutbox.Event event) {
        if ("CREATED".equals(event.type())) send("order.created", event.id(), event.payload(), null);
        else if ("TIMEOUT".equals(event.type())) timeout(event.id(), event.payload(), event.deadline());
        else throw new IllegalArgumentException("Unknown order event type");
    }
    public void timeout(String eventId, String payload, LocalDateTime deadline) {
        long remaining = Duration.between(LocalDateTime.now(ZoneId.of("Asia/Singapore")), deadline).toMillis();
        send(remaining <= 0 ? "timeout.cancel" : topology.delayRoutingKey(), eventId, payload,
                remaining <= 0 ? null : Long.toString(remaining + 1));
    }
    private void send(String routingKey, String eventId, String payload, String expiration) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        if (expiration != null) properties.setExpiration(expiration);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(topology.exchange(), routingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
        try {
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Order event was not routed/confirmed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Order event publish interrupted", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Order event publish not confirmed", exception);
        }
    }
}
