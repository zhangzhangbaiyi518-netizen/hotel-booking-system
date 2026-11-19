package com.hotel.hotelbookingsystem.messaging;

import com.hotel.hotelbookingsystem.config.BookingReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OrderOutboxDispatcher.class);
    private final OrderOutbox outbox;
    private final OrderMessagePublisher publisher;
    private final BookingReadiness readiness;
    public OrderOutboxDispatcher(OrderOutbox outbox, OrderMessagePublisher publisher, BookingReadiness readiness) {
        this.outbox = outbox; this.publisher = publisher; this.readiness = readiness;
    }
    @Scheduled(fixedDelayString = "${hotel.orders.outbox-interval-ms}", initialDelay = 1000)
    public void dispatch() {
        if (!readiness.isReady()) return;
        for (String id : outbox.pending()) {
            OrderOutbox.Event event = null;
            try {
                event = outbox.claim(id);
                if (event == null) continue;
                publisher.publish(event);
                outbox.sent(event);
            } catch (Exception exception) {
                if (event != null) {
                    log.warn("Order event {} pending retry: {}", id, exception.toString());
                    try { outbox.retry(event); } catch (Exception retryFailure) { log.warn("Order event lease will retry {}", id); }
                    // A broker outage should not spend the scheduler cycle failing every pending row.
                    break;
                }
                log.debug("Order event {} still locked or claimed elsewhere", id, exception);
            }
        }
    }
}
