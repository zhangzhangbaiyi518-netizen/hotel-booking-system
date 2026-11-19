package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import com.hotel.hotelbookingsystem.service.BookingTransactions;
import com.hotel.hotelbookingsystem.client.CatalogClient;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import com.hotel.hotelbookingsystem.service.StayOrderService.StayOrder;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/hotel/orders")
public class StayOrderController {
    private final StayOrderService orders;

    private final CatalogClient catalog;
    private final BookingTransactions bookings;

    public StayOrderController(StayOrderService orders, CatalogClient catalog, BookingTransactions bookings) {
        this.orders = orders;
        this.catalog = catalog;
        this.bookings = bookings;
    }

    public record CreateOrderRequest(Long roomTypeId, String requestId) {}
    public record RefundRequest(String reason) {}

    @GetMapping("/all")
    public List<StayOrder> all(@AuthenticationPrincipal Jwt jwt) {
        return orders.listAll(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{id}/cancel")
    public StayOrder cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return bookings.cancel(Long.valueOf(jwt.getSubject()), id);
    }

    @DeleteMapping("/{id}/refund")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        orders.deleteRefund(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/refund-request")
    public StayOrder requestRefund(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return orders.requestRefund(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/refund")
    public StayOrder refund(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id,
                            @RequestBody RefundRequest request) {
        return bookings.refund(Long.valueOf(jwt.getSubject()), id, request.reason());
    }

    @GetMapping("/refunds")
    public List<StayOrder> refunds(@AuthenticationPrincipal Jwt jwt) {
        return orders.listRefunds(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping
    @SentinelResource(value = "reservation:create", blockHandler = "reservationBlocked")
    @ResponseStatus(HttpStatus.CREATED)
    public StayOrder create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateOrderRequest request) {
        RoomSnapshot room = room(jwt, request.roomTypeId());
        return bookings.create(Long.valueOf(jwt.getSubject()), room, request.requestId());
    }

    @GetMapping("/inventory")
    public RoomInventory inventory(@AuthenticationPrincipal Jwt jwt, @RequestParam Long roomTypeId) {
        RoomSnapshot room = room(jwt, roomTypeId);
        return bookings.inventory(room.roomTypeId());
    }

    private RoomSnapshot room(Jwt jwt, Long roomTypeId) {
        if (roomTypeId == null || roomTypeId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效房型");
        }
        ResponseEntity<RoomSnapshot> response = catalog.room("Bearer " + jwt.getTokenValue(), roomTypeId);
        if (response != null && response.getStatusCode().value() == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房型或酒店不存在");
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "预约服务暂不可用，请稍后重试");
        }
        return response.getBody();
    }

    @GetMapping("/{id}")
    public StayOrder get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return orders.get(Long.valueOf(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/pay")
    public StayOrder pay(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        return orders.pay(Long.valueOf(jwt.getSubject()), id);
    }

    @GetMapping
    public List<StayOrder> list(@AuthenticationPrincipal Jwt jwt) {
        return orders.listAwaitingCheckIn(Long.valueOf(jwt.getSubject()));
    }

    public StayOrder reservationBlocked(Jwt jwt, CreateOrderRequest request, BlockException exception) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "预约请求过于频繁，请稍后重试");
    }

    @ExceptionHandler({io.seata.common.exception.FrameworkException.class,
            io.seata.core.exception.TransactionException.class,
            org.springframework.dao.TransientDataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String, String>> transactionUnavailable(Exception exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of("message", "订单事务暂不可用，请稍后查询订单或使用原请求编号重试"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode());
        if (exception.getStatusCode().value() == 429) {
            response.header("Retry-After", "1");
        }
        return response.body(Map.of("message", exception.getReason()));
    }
}
