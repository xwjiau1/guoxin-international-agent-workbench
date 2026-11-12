package com.guoxin.assistant.web;

import com.guoxin.assistant.domain.Actor;
import com.guoxin.assistant.infrastructure.AgentStore;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import static com.guoxin.assistant.infrastructure.AgentStore.*;

@Service
public class AuthService {
    private final AgentStore store;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AgentStore store) {
        this.store = store;
    }

    public Actor actor(String id) {
        return switch (id) {
            case "11111111-1111-1111-1111-111111111111" -> new Actor(id, "DEMO001", "张晨", "综合管理部");
            case "22222222-2222-2222-2222-222222222222" -> new Actor(id, "DEMO002", "李敏", "综合管理部");
            case "33333333-3333-3333-3333-333333333333" -> new Actor(id, "DEMO003", "王宁", "财务部");
            default -> throw new ApiException(401, "UNAUTHENTICATED", "请重新登录");
        };
    }

    public Map<String, Object> login(String username, String password) {
        if (!"Demo@123".equals(password))
            throw new ApiException(401, "INVALID_CREDENTIALS", "账号或密码不正确");
        String id =
                switch (username.toUpperCase(Locale.ROOT)) {
                    case "DEMO001" -> "11111111-1111-1111-1111-111111111111";
                    case "DEMO002" -> "22222222-2222-2222-2222-222222222222";
                    case "DEMO003" -> "33333333-3333-3333-3333-333333333333";
                    default -> throw new ApiException(401, "INVALID_CREDENTIALS", "账号或密码不正确");
                };
        String token = token(), csrf = token();
        store.jdbc.update(
                "insert into gxa_sessions(token_hash,user_id,csrf_token,expires_at) values(?,?,?,?)",
                hash(token),
                uuid(id),
                csrf,
                OffsetDateTime.now().plusHours(8));
        return map("token", token, "actor", publicActor(actor(id), csrf));
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sessionHash(HttpServletRequest req) {
        if (req.getCookies() != null)
            for (Cookie c : req.getCookies())
                if ("GX_SESSION".equals(c.getName())) return hash(c.getValue());
        return null;
    }

    public Map<String, Object> session(HttpServletRequest req) {
        String h = sessionHash(req);
        if (h == null) throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
        var rows =
                store.rows("select * from gxa_sessions where token_hash=? and expires_at>now()", h);
        if (rows.isEmpty()) throw new ApiException(401, "SESSION_EXPIRED", "登录已过期，请重新登录");
        return rows.getFirst();
    }

    public Map<String, Object> publicActor(Actor a, String csrf) {
        return map(
                "id",
                a.id(),
                "employeeNo",
                a.employeeNo(),
                "name",
                a.name(),
                "department",
                a.department(),
                "capabilities",
                List.of("passport-borrow", "todo-review"),
                "csrfToken",
                csrf);
    }

    public void logout(HttpServletRequest req) {
        store.jdbc.update("delete from gxa_sessions where token_hash=?", sessionHash(req));
    }

    public static class ApiException extends RuntimeException {
        public final int status;
        public final String code;

        public ApiException(int s, String c, String m) {
            super(m);
            status = s;
            code = c;
        }
    }
}
