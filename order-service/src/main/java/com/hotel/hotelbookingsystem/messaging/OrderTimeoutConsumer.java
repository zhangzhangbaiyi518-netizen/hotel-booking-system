package com.hotel.hotelbookingsystem.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.hotelbookingsystem.config.BookingReadiness;
import com.hotel.hotelbookingsystem.service.BookingTransactions;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);
    private final BookingTransactions bookings;
    private final StayOrderService orders;
    private final OrderMessagePublisher publisher;
    private final ObjectMapper json;
    private final BookingReadiness readiness;
    private final RabbitListenerEndpointRegistry listeners;
    public OrderTimeoutConsumer(BookingTransactions bookings, StayOrderService orders, OrderMessagePublisher publisher,
            ObjectMapper json, BookingReadiness readiness, RabbitListenerEndpointRegistry listeners) {
        this.bookings = bookings; this.orders = orders; this.publisher = publisher;
        this.json = json; this.readiness = readiness; this.listeners = listeners;
    }
    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void startConsumers() {
        if (!readiness.isReady() || listeners.isRunning()) return;
        try { listeners.start(); }
        catch (Exception exception) { log.warn("Timeout consumer pending broker recovery: {}", exception.toString()); }
    }

    @RabbitListener(id = "orderTimeoutListener", queues = "#{orderRabbitConfiguration.timeoutQueue()}")
    public void timeout(Message message) {
        if (!readiness.isReady()) throw new IllegalStateException("Order migration is still running");
        OrderMessages.Timeout event;
        try {
            event = json.readValue(message.getBody(), OrderMessages.Timeout.class);
            if (event.orderId() == null || event.orderId().isBlank() || event.orderId().length() > 36
                    || !("timeout:" + event.orderId()).equals(event.eventId())) throw new IllegalArgumentException("Invalid timeout event");
        } catch (Exception exception) { throw new AmqpRejectAndDontRequeueException("Invalid order timeout message", exception); }
        var remaining = bookings.cancelExpired(event.orderId());
        if (remaining != null) publisher.timeout(event.eventId(), new String(message.getBody(), StandardCharsets.UTF_8), remaining);
        else log.info("Completed timeout check for order {} (dead-lettered={})", event.orderId(),
                message.getMessageProperties().getXDeathHeader() != null);
        // AUTO ack occurs only after the Seata proxy returns from commit and any re-publish is confirmed.
    }

    @Scheduled(fixedDelayString = "${hotel.orders.timeout-recovery-interval-ms}", initialDelay = 60000)
    public void recoverOverdueOrders() {
        if (!readiness.isReady()) return;
        for (String id : orders.overdueOrders()) {
            try { bookings.cancelExpired(id); }
            catch (Exception exception) { log.warn("Overdue order {} pending recovery: {}", id, exception.toString()); }
        }
    }
}
