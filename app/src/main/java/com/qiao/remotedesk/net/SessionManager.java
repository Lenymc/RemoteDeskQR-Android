package com.qiao.remotedesk.net;

import com.qiao.remotedesk.model.ConnectionProfile;
import com.qiao.remotedesk.model.LoopbackPool;
import com.qiao.remotedesk.model.ProxySettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 活动隧道会话的单例注册表，按连接 Id 管理。 */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<String, TunnelServer> sessions = new LinkedHashMap<String, TunnelServer>();

    private SessionManager() {
    }

    public static SessionManager get() {
        return INSTANCE;
    }

    /** 重复 start 同一 id 会先停掉旧会话；回环地址未分配时分配并写回 profile。 */
    public synchronized TunnelServer startSession(ConnectionProfile p, ProxySettings globalProxy,
                                                  TunnelServer.LogListener log) throws IOException {
        if (p == null) {
            throw new IOException("连接配置为空");
        }
        String id = p.id == null ? "" : p.id;
        if (!id.isEmpty()) {
            stopSession(id);
        }

        ProxySettings effective = p.effectiveProxy(globalProxy);
        ProxyConnector connector = ProxyFactory.create(effective);

        String bind = p.loopbackAddress;
        if (!LoopbackPool.isValid(bind)) {
            List<String> inUse = new ArrayList<String>();
            for (TunnelServer t : sessions.values()) {
                inUse.add(t.bindAddress());
            }
            bind = LoopbackPool.allocate(inUse);
            p.loopbackAddress = bind;
        }

        TunnelServer server = TunnelServer.start(connector, p.host, p.port, bind);
        if (log != null) {
            server.setLogListener(log);
        }
        if (!id.isEmpty()) {
            sessions.put(id, server);
        }
        return server;
    }

    public synchronized void stopSession(String profileId) {
        if (profileId == null) {
            return;
        }
        TunnelServer s = sessions.remove(profileId);
        if (s != null) {
            s.stop();
        }
    }

    public synchronized TunnelServer get(String profileId) {
        return profileId == null ? null : sessions.get(profileId);
    }

    public synchronized Collection<String> activeIds() {
        return new ArrayList<String>(sessions.keySet());
    }

    public synchronized void stopAll() {
        for (TunnelServer s : sessions.values()) {
            s.stop();
        }
        sessions.clear();
    }
}
