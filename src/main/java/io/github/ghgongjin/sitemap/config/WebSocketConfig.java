package io.github.ghgongjin.sitemap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * @ClassName WebSocketConfig
 * @Description WebSocket 配置类
 * @Author gj
 * @Date 2026/3/9
 * @Version 1.0
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册 STOMP 协议的端点，支持 SockJS
     registry.addEndpoint("/ws-progress")
                .setAllowedOrigins("*")
               .withSockJS()
               .setHeartbeatTime(25000);  // 设置心跳时间
        
        log.info("WebSocket 端点已注册：/ws-progress");
    }

    @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 启用简单的内存消息代理
    registry.enableSimpleBroker("/topic");
        
        // 设置应用程序前缀
    registry.setApplicationDestinationPrefixes("/app");
        
        // 设置应用目的地前缀
    registry.setUserDestinationPrefix("/user");
        
        log.info("WebSocket 消息代理已配置");
    }
}
