package com.ruoyi.salesforce.websocket;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 部署日志 WebSocket 服务
 * URL: /websocket/deploy/{deploymentId}
 */
@ServerEndpoint("/websocket/deploy/{deploymentId}")
@Component
public class DeployWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(DeployWebSocketServer.class);

    // 静态变量，用来存储连接
    private static ConcurrentHashMap<String, CopyOnWriteArraySet<Session>> sessionPool = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("deploymentId") String deploymentId) {
        try {
            // 【关键步骤】手动鉴权
            // 因为我们在 SecurityConfig 中放行了 /websocket/**，所以这里必须自己检查 Token
            if (!validateToken(session)) {
                log.warn("WebSocket鉴权失败，强制关闭连接: deploymentId={}", deploymentId);
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Auth Failed"));
                return;
            }

            // 鉴权通过，加入连接池
            sessionPool.computeIfAbsent(deploymentId, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("WebSocket连接建立: Id={}, 当前在线: {}", deploymentId, sessionPool.get(deploymentId).size());

        } catch (Exception e) {
            log.error("WebSocket连接异常", e);
        }
    }

    /**
     * 从 QueryString 中解析 Token 并校验
     */
    private boolean validateToken(Session session) {
        try {
            // 获取 URL 参数部分 (例如: token=eyJhbG...)
            String queryString = session.getQueryString();
            if (StringUtils.isEmpty(queryString)) return false;

            // 解析参数
            String token = null;
            String[] params = queryString.split("&");
            for (String param : params) {
                if (param.startsWith("token=")) {
                    token = param.substring(6); // 去掉 "token="
                    break;
                }
            }

            if (StringUtils.isEmpty(token)) return false;

            // 使用若依的 TokenService 校验
            // 注意：WebSocket 是非 Spring 管理的多例对象，需用 SpringUtils 获取 Bean
            TokenService tokenService = SpringUtils.getBean(TokenService.class);
            LoginUser loginUser = tokenService.getLoginUser(token);

            return loginUser != null;
        } catch (Exception e) {
            log.error("WS Token校验异常", e);
            return false;
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("deploymentId") String deploymentId) {
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(deploymentId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionPool.remove(deploymentId);
            }
        }
        log.info("WebSocket连接断开: Id={}", deploymentId);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        // 忽略正常的关闭错误
        if(error.getMessage() != null && error.getMessage().contains("Connection reset by peer")) {
            return;
        }
        log.error("WebSocket发生错误", error);
    }

    public static void sendMessage(Long deploymentId, String message) {
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(String.valueOf(deploymentId));
        if (sessions != null && !sessions.isEmpty()) {
            for (Session session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.getBasicRemote().sendText(message);
                        }
                    } catch (IOException e) {
                        log.error("推送消息失败: " + deploymentId, e);
                    }
                }
            }
        }
    }

    public static void sendMessage(String key, String message) {
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(key);
        if (sessions != null && !sessions.isEmpty()) {
            for (Session session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.getBasicRemote().sendText(message);
                        }
                    } catch (IOException e) {
                        log.error("推送消息失败: " + key, e);
                    }
                }
            }
        }
    }
}