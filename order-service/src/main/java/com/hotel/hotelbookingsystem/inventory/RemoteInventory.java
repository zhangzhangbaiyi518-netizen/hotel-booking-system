package com.hotel.hotelbookingsystem.inventory;

import com.hotel.hotelbookingsystem.client.InventoryClient;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import feign.FeignException;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RemoteInventory implements InventoryPort {
    private final InventoryClient client;
    private final JwtEncoder encoder;

    public RemoteInventory(InventoryClient client, SecretKey key) {
        this.client = client;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    private String authorization(Long userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("hotel-system").subject(userId.toString())
                .claim("service", "inventory").issuedAt(now).expiresAt(now.plusSeconds(120)).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private ResponseStatusException unavailable(FeignException exception) {
        HttpStatus status = exception.status() == 409 ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        return new ResponseStatusException(status,
                status == HttpStatus.CONFLICT ? "房型库存不足或订单状态已变化" : "库存服务暂不可用，请稍后重试", exception);
    }

    @Override
    public RoomInventory inventory(Long roomTypeId) {
        try { return client.inventory(authorization(0L), roomTypeId); }
        catch (FeignException e) { throw unavailable(e); }
    }

    @Override
    public void reserve(Long userId, String orderId, Long roomTypeId) {
        try { client.reserve(authorization(userId), new InventoryClient.Reservation(orderId, roomTypeId, null)); }
        catch (FeignException e) { throw unavailable(e); }
    }

    @Override
    public void release(Long userId, String orderId) {
        try { client.release(authorization(userId), orderId); }
        catch (FeignException e) { throw unavailable(e); }
    }

    @Override
    public void importHeld(Long userId, String orderId, Long roomTypeId, Integer total) {
        try { client.importHeld(authorization(userId), new InventoryClient.Reservation(orderId, roomTypeId, total)); }
        catch (FeignException e) { throw unavailable(e); }
    }
}
