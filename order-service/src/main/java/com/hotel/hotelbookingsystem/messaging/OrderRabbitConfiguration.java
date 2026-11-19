package com.hotel.hotelbookingsystem.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderRabbitConfiguration {
    private final String prefix;
    private final long timeoutMs;
    public OrderRabbitConfiguration(@Value("${hotel.orders.mq-prefix}") String prefix,
            @Value("${hotel.orders.payment-timeout-ms}") long timeoutMs) {
        if (prefix.isBlank() || timeoutMs < 1000) throw new IllegalArgumentException("Invalid order messaging configuration");
        this.prefix = prefix;
        this.timeoutMs = timeoutMs;
    }
    public String exchange() { return prefix + ".events"; }
    public String notificationQueue() { return prefix + ".created"; }
    public String timeoutQueue() { return prefix + ".timeout.cancel"; }
    public String delayQueue() { return prefix + ".timeout.delay." + timeoutMs; }

    @Bean
    public Declarables orderRabbitTopology() {
        var events = new DirectExchange(exchange(), true, false);
        var failed = new DirectExchange(prefix + ".failed", true, false);
        var notifications = QueueBuilder.durable(notificationQueue()).quorum().build();
        var delay = QueueBuilder.durable(delayQueue()).quorum()
                .withArgument("x-message-ttl", timeoutMs)
                .withArgument("x-dead-letter-exchange", exchange())
                .withArgument("x-dead-letter-routing-key", "timeout.cancel")
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish").build();
        var cancel = QueueBuilder.durable(timeoutQueue()).quorum()
                .deadLetterExchange(prefix + ".failed").deadLetterRoutingKey("timeout.failed")
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish").build();
        var failures = QueueBuilder.durable(prefix + ".timeout.failed").quorum().build();
        return new Declarables(events, failed, notifications, delay, cancel, failures,
                BindingBuilder.bind(notifications).to(events).with("order.created"),
                BindingBuilder.bind(delay).to(events).with("timeout.delay." + timeoutMs),
                BindingBuilder.bind(cancel).to(events).with("timeout.cancel"),
                BindingBuilder.bind(failures).to(failed).with("timeout.failed"));
    }
    public String delayRoutingKey() { return "timeout.delay." + timeoutMs; }
}
