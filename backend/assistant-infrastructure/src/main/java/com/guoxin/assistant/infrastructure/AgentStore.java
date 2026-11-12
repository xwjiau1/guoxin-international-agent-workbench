package com.guoxin.assistant.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.domain.Actor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AgentStore {
    public final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public final TransactionTemplate tx;

    public AgentStore(
            JdbcTemplate jdbc, ObjectMapper json, DataSource ds, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        new ResourceDatabasePopulator(new ClassPathResource("schema-agent.sql")).execute(ds);
    }

    public String encode(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化数据", e);
        }
    }

    public String encodeCanonical(Object v) {
        try {
            return json.writer()
                    .with(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化数据", e);
        }
    }

    public Map<String, Object> decode(Object v) {
        if (v == null) return new LinkedHashMap<>();
        try {
            return json.readValue(
                    v.toString(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("无法读取持久化任务", e);
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static UUID uuid(Object v) {
        return UUID.fromString(String.valueOf(v));
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> ownedConversation(String id, Actor a) {
        return one(
                "select * from gxa_conversations where id=? and user_id=?", uuid(id), uuid(a.id()));
    }

    public Map<String, Object> ownedTask(String id, Actor a) {
        return one("select * from gxa_tasks where id=? and user_id=?", uuid(id), uuid(a.id()));
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw new ResourceMissing();
        return rows.getFirst();
    }

    public Map<String, Object> conversation(Map<String, Object> r) {
        return map(
                "id",
                r.get("id").toString(),
                "title",
                r.get("title"),
                "activeTaskId",
                str(r.get("active_task_id")),
                "lastEventId",
                r.get("last_event_seq").toString(),
                "createdAt",
                r.get("created_at").toString(),
                "updatedAt",
                r.get("updated_at").toString());
    }

    public Map<String, Object> task(Map<String, Object> r) {
        var s = decode(r.get("state"));
        var active =
                rows(
                        "select id from gxa_commands where task_id=? and status in ('QUEUED','RUNNING') order by created_at limit 1",
                        r.get("id"));
        return map(
                "id",
                r.get("id").toString(),
                "conversationId",
                r.get("conversation_id").toString(),
                "type",
                s.getOrDefault("kind", "GENERAL"),
                "status",
                r.get("status"),
                "version",
                r.get("version"),
                "activeRunId",
                active.isEmpty() ? null : active.getFirst().get("id").toString(),
                "draftId",
                s.get("draftId"),
                "operationId",
                s.get("operationId"),
                "components",
                s.getOrDefault("components", List.of()),
                "allowedActions",
                List.of(
                        "PROVIDE_FIELDS",
                        "UPDATE_REVIEW",
                        "CONFIRM",
                        "SAVE_DRAFT",
                        "RESUME",
                        "PAUSE",
                        "CANCEL",
                        "RETRY_FAILED"),
                "updatedAt",
                iso(r.get("updated_at")));
    }

    public void saveState(String taskId, Map<String, Object> state) {
        jdbc.update(
                "update gxa_tasks set state=?::jsonb,status=?,version=version+1,updated_at=now() where id=?",
                encode(state),
                String.valueOf(state.getOrDefault("status", "RUNNING")),
                uuid(taskId));
    }

    public Map<String, Object> event(
            String conversationId,
            String taskId,
            String runId,
            String type,
            Map<String, Object> payload) {
        return tx.execute(
                x -> {
                    long seq =
                            jdbc.queryForObject(
                                    "update gxa_conversations set last_event_seq=last_event_seq+1,updated_at=now() where id=? returning last_event_seq",
                                    Long.class,
                                    uuid(conversationId));
                    jdbc.update(
                            "insert into gxa_events(conversation_id,event_seq,task_id,run_id,type,payload) values(?,?,?,?,?,?::jsonb)",
                            uuid(conversationId),
                            seq,
                            taskId == null ? null : uuid(taskId),
                            runId == null ? null : uuid(runId),
                            type,
                            encode(payload));
                    return map(
                            "schemaVersion",
                            "1.0",
                            "eventId",
                            String.valueOf(seq),
                            "conversationId",
                            conversationId,
                            "taskId",
                            taskId,
                            "runId",
                            runId,
                            "traceId",
                            runId,
                            "occurredAt",
                            java.time.OffsetDateTime.now().toString(),
                            "payload",
                            payload,
                            "type",
                            type);
                });
    }

    public List<Map<String, Object>> events(String conv, long after) {
        return rows(
                        "select * from gxa_events where conversation_id=? and event_seq>? order by event_seq limit 500",
                        uuid(conv),
                        after)
                .stream()
                .map(
                        r ->
                                map(
                                        "schemaVersion",
                                        "1.0",
                                        "eventId",
                                        r.get("event_seq").toString(),
                                        "conversationId",
                                        conv,
                                        "taskId",
                                        str(r.get("task_id")),
                                        "runId",
                                        str(r.get("run_id")),
                                        "traceId",
                                        str(r.get("run_id")),
                                        "occurredAt",
                                        r.get("occurred_at").toString(),
                                        "type",
                                        r.get("type"),
                                        "payload",
                                        decode(r.get("payload"))))
                .toList();
    }

    public static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public static String iso(Object o) {
        return o instanceof java.sql.Timestamp t
                ? t.toInstant().toString()
                : o == null ? null : o.toString();
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i].toString(), pairs[i + 1]);
        return m;
    }

    public static class ResourceMissing extends RuntimeException {}
}
