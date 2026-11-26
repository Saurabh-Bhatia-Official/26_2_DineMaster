package com.dinemaster.kitchen.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.dinemaster.security.BranchScopeService;
import org.springframework.security.access.AccessDeniedException;
import com.dinemaster.orders.service.BillRequestedEvent;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableWebSocketMessageBroker
class KitchenWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final BranchScopeService branchScope;
    KitchenWebSocketConfiguration(BranchScopeService branchScope) { this.branchScope = branchScope; }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) { registry.enableSimpleBroker("/topic"); }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) { registry.addEndpoint("/ws/kitchen"); }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && accessor.getCommand() == StompCommand.SUBSCRIBE) {
                    Authentication auth = accessor.getUser() instanceof Authentication a ? a : null;
                    String destination = accessor.getDestination();
                    if (auth == null || destination == null)
                        throw new AccessDeniedException("Authenticated kitchen access is required");
                    String[] parts = destination.split("/");
                    if (parts.length < 4 || !"topic".equals(parts[1])
                            || !("kitchen".equals(parts[2]) || "cashier".equals(parts[2])))
                        throw new AccessDeniedException("Unsupported subscription destination");
                    String permission = "kitchen".equals(parts[2]) ? "kot:view" : "bill:generate";
                    if (!auth.getAuthorities().contains(new SimpleGrantedAuthority(permission)))
                        throw new AccessDeniedException("Subscription permission is required");
                    try { branchScope.requireBranch(auth, Long.valueOf(parts[3])); }
                    catch (RuntimeException ex) { throw new AccessDeniedException("Kitchen subscription is outside branch scope"); }
                }
                return message;
            }
        });
    }
}

@Component
class KitchenWebSocketPublisher {
    private final SimpMessagingTemplate messaging;
    KitchenWebSocketPublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKitchenEvent(KitchenEventService.KitchenEventCommitted event) {
        String destination = event.station() == null ? "/topic/kitchen/" + event.branchId()
                : "/topic/kitchen/" + event.branchId() + "/" + event.station();
        messaging.convertAndSend(destination, event.payload());
        messaging.convertAndSend("/topic/kitchen/" + event.branchId() + "/events", event.payload());
    }
}

@Component
class CashierBillRequestPublisher {
    private final SimpMessagingTemplate messaging;
    CashierBillRequestPublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBillRequested(BillRequestedEvent event) {
        messaging.convertAndSend("/topic/cashier/" + event.branchId(), java.util.Map.of(
                "event", "bill.requested", "branchId", event.branchId(), "orderId", event.orderId(),
                "orderNo", event.orderNo() == null ? "" : event.orderNo(), "tableId", event.tableId(),
                "requestedBy", event.requestedBy()));
    }
}
