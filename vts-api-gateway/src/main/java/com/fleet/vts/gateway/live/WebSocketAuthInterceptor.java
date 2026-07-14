package com.fleet.vts.gateway.live;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates the STOMP CONNECT frame with the same JWT the REST API uses.
 *
 * <p>The HTTP handshake on {@code /ws} is public (SockJS cannot set an Authorization
 * header on it), so without this the live feed was wide open: anyone could subscribe to
 * {@code /topic/fleet/live} and watch the whole fleet without a token. The token is
 * instead carried on the CONNECT frame, verified here, and the resulting principal is
 * attached to the session.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public WebSocketAuthInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;   // only the CONNECT frame carries credentials
        }

        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new IllegalArgumentException("WebSocket CONNECT without a bearer token");
        }
        Jwt jwt = jwtDecoder.decode(header.substring(7));   // throws on invalid/expired

        List<String> roles = jwt.getClaimAsStringList("roles");
        List<GrantedAuthority> authorities = (roles == null ? List.<String>of() : roles).stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        Authentication auth = new UsernamePasswordAuthenticationToken(jwt, null, authorities);
        accessor.setUser(auth);
        return message;
    }
}
