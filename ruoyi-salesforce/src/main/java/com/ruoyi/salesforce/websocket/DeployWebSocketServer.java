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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 部署日志 WebSocket 服务
 * URL: /websocket/deploy/{deploymentId}
 */
@ServerEndpoint("/websocket/{sid}")
@Component
public class DeployWebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(DeployWebSocketServer.class);

    // 静态变量，用来存储连接
    private static ConcurrentHashMap<String, CopyOnWriteArraySet<Session>> sessionPool = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        try {
            // 【关键步骤】手动鉴权
            // 因为我们在 SecurityConfig 中放行了 /websocket/**，所以这里必须自己检查 Token
            if (!validateToken(session)) {
                log.warn("WebSocket鉴权失败，强制关闭连接: deploymentId={}", sid);
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Auth Failed"));
                return;
            }

            // 鉴权通过，加入连接池
            sessionPool.computeIfAbsent(sid, k -> new CopyOnWriteArraySet<>()).add(session);
            log.info("WebSocket连接建立: Id={}, 当前在线: {}", sid, sessionPool.get(sid).size());

        } catch (Exception e) {
            log.error("WebSocket连接异常", e);
        }
    }

    /**
     * 校验 Token
     */
    private boolean validateToken(Session session) {
        try {
            String queryString = session.getQueryString();
            if (StringUtils.isEmpty(queryString)) return false;

            String token = null;
            String[] params = queryString.split("&");
            for (String param : params) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }

            if (StringUtils.isEmpty(token)) return false;

            TokenService tokenService = SpringUtils.getBean(TokenService.class);
            LoginUser loginUser = tokenService.getLoginUser(token);
            return loginUser != null;
        } catch (Exception e) {
            log.error("WS Token校验异常", e);
            return false;
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("sid") String sid) {
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(sid);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionPool.remove(sid);
            }
        }
        log.info("WebSocket连接断开: sid={}", sid);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if(error.getMessage() != null && error.getMessage().contains("Connection reset by peer")) {
            return; // 忽略常规断开
        }
        log.error("WebSocket发生错误", error);
    }

    public static void sendMessage(Long deploymentId, String message) {
        String key = String.valueOf(deploymentId);
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(key);
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

            // 【新增优化】如果消息表明任务已结束(done:true)，服务端主动关闭所有相关连接
            // 解决多端同步问题及防止连接泄露
            if (message.contains("\"done\":true")) {
                closeAllSessions(key);
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

            // 【新增优化】同上，针对 String key 的重载方法也加上清理逻辑
            if (message.contains("\"done\":true")) {
                closeAllSessions(key);
            }
        }
    }

    /**
     * 【新增方法】主动关闭指定 Key 下的所有 WebSocket 会话并移除连接池
     */
    private static void closeAllSessions(String key) {
        CopyOnWriteArraySet<Session> sessions = sessionPool.get(key);
        if (sessions != null) {
            for (Session session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.close(); // 发送关闭帧给客户端
                    }
                } catch (IOException e) {
                    log.warn("关闭会话异常: " + e.getMessage());
                }
            }
            // 从池中移除，释放内存
            sessionPool.remove(key);
            log.info("任务结束，服务端已强制断开并清理所有连接: Id={}", key);
        }
    }
}