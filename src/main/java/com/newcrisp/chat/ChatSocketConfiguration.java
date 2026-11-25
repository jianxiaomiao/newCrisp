package com.newcrisp.chat;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Side;
import com.newcrisp.identity.IdentityError;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
class ChatSocketConfiguration implements WebSocketConfigurer {
    private final ChatWebSocket socket;
    private final ChatIdentity identity;

    ChatSocketConfiguration(ChatWebSocket socket, ChatIdentity identity) {
        this.socket = socket;
        this.identity = identity;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/customer", "/ws/staff").setAllowedOrigins(identity.publicOrigin())
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                                   Map<String, Object> attributes) {
                        try {
                            Side side = request.getURI().getPath().endsWith("/customer") ? Side.CUSTOMER : Side.STAFF;
                            var servlet = ((ServletServerHttpRequest) request).getServletRequest();
                            identity.requireBrowserOrigin(servlet);
                            if (servlet.getQueryString() != null) throw IdentityError.unauthorized();
                            attributes.put(ChatWebSocket.SIDE, side);
                            return true;
                        } catch (IdentityError error) {
                            response.setStatusCode(error.status);
                            return false;
                        }
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception error) {}
                });
    }
}
