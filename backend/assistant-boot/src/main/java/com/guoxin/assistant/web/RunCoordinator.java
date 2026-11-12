package com.guoxin.assistant.web;

import com.guoxin.assistant.application.*;
import com.guoxin.assistant.domain.Actor;
import com.guoxin.assistant.infrastructure.AgentStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.guoxin.assistant.infrastructure.AgentStore.*;
import static com.guoxin.assistant.web.AuthService.ApiException;

@Service
public class RunCoordinator {
    private final AgentStore store;
    private final AgentRuntime runtime;
    private final AuthService auth;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agent-worker").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Connection lock;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public RunCoordinator(AgentStore store, AgentRuntime runtime, AuthService auth, DataSource ds)
            throws Exception {
        this.store = store;
        this.runtime = runtime;
        this.auth = auth;
        lock = ds.getConnection();
        try (var s = lock.createStatement();
                var rs = s.executeQuery("select pg_try_advisory_lock(771203411)")) {
            rs.next();
            if (!rs.getBoolean(1)) {
                lock.close();
                throw new IllegalStateException("本地Agent执行器已运行，请停止旧进程后启动");
            }
        }
        store.jdbc.update("update gxa_commands set status='QUEUED' where status='RUNNING'");
    }

    public Map<String, Object> accept(
            Actor actor,
            String convId,
            String taskId,
            Map<String, Object> body,
            boolean action,
            String sessionHash) {
        if (!action
                && (!(body.get("text") instanceof String text)
                        || text.isBlank()
                        || text.length() > 8000))
            throw new ApiException(422, "INVALID_TEXT", "请输入1至8000字的消息");
        if (action && !(body.get("payload") instanceof Map))
            throw new ApiException(422, "INVALID_ACTION", "组件动作需要有效的参数对象");
        UUID dedupe = uuid(body.get(action ? "actionId" : "clientMessageId"));
        String reqHash = hash(store.encodeCanonical(body));
        return store.tx.execute(
                tx -> {
                    store.one(
                            "select * from gxa_conversations where id=? and user_id=? for update",
                            uuid(convId),
                            uuid(actor.id()));
                    var existing =
                            store.rows(
                                    "select * from gxa_commands where user_id=? and conversation_id=? and dedupe_key=?",
                                    uuid(actor.id()),
                                    uuid(convId),
                                    dedupe);
                    if (!existing.isEmpty()) {
                        var row = existing.getFirst();
                        if (!reqHash.equals(row.get("request_hash")))
                            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一请求编号不能使用不同内容");
                        return map(
                                "runId",
                                row.get("id").toString(),
                                "taskId",
                                row.get("task_id").toString());
                    }
                    String selected = taskId;
                    if (selected == null) {
                        var c = store.ownedConversation(convId, actor);
                        selected = str(c.get("active_task_id"));
                    }
                    if (selected == null) {
                        selected = UUID.randomUUID().toString();
                        store.jdbc.update(
                                "insert into gxa_tasks(id,user_id,conversation_id) values(?,?,?)",
                                uuid(selected),
                                uuid(actor.id()),
                                uuid(convId));
                        store.jdbc.update(
                                "update gxa_conversations set active_task_id=? where id=?",
                                uuid(selected),
                                uuid(convId));
                    }
                    var task =
                            store.one(
                                    "select * from gxa_tasks where id=? and user_id=? for update",
                                    uuid(selected),
                                    uuid(actor.id()));
                    if (!convId.equals(task.get("conversation_id").toString()))
                        throw new AgentStore.ResourceMissing();
                    if (action) {
                        Object expected = body.get("expectedTaskVersion");
                        if (!(expected instanceof Number)
                                || ((Number) expected).longValue()
                                        != ((Number) task.get("version")).longValue())
                            throw new ApiException(409, "TASK_VERSION_CONFLICT", "任务已更新，请刷新后再操作");
                        String type = String.valueOf(body.get("type"));
                        if (!Set.of(
                                        "PROVIDE_FIELDS",
                                        "UPDATE_REVIEW",
                                        "CONFIRM",
                                        "SAVE_DRAFT",
                                        "RESUME",
                                        "PAUSE",
                                        "CANCEL",
                                        "RETRY_FAILED")
                                .contains(type)) throw new IllegalArgumentException();
                        store.jdbc.update(
                                "update gxa_tasks set version=version+1 where id=?",
                                uuid(selected));
                    } else {
                        String text = String.valueOf(body.getOrDefault("text", ""));
                        if (text.isBlank() || text.length() > 8000)
                            throw new ApiException(422, "INVALID_TEXT", "请输入1至8000字的消息");
                    }
                    String runId = UUID.randomUUID().toString(),
                            messageId = UUID.randomUUID().toString();
                    store.jdbc.update(
                            "insert into gxa_commands(id,user_id,conversation_id,task_id,dedupe_key,request_hash,payload,trace_id,session_hash) values(?,?,?,?,?,?,?::jsonb,?,?)",
                            uuid(runId),
                            uuid(actor.id()),
                            uuid(convId),
                            uuid(selected),
                            dedupe,
                            reqHash,
                            store.encode(map("action", action, "body", body)),
                            runId,
                            sessionHash);
                    if (!action) {
                        store.jdbc.update(
                                "insert into gxa_messages(id,conversation_id,run_id,client_message_id,role,content) values(?,?,?,?,?,?)",
                                uuid(messageId),
                                uuid(convId),
                                uuid(runId),
                                dedupe,
                                "USER",
                                body.get("text"));
                        store.jdbc.update(
                                "update gxa_conversations set title=case when title='新对话' then ? else title end where id=?",
                                String.valueOf(body.get("text"))
                                        .substring(
                                                0,
                                                Math.min(
                                                        24,
                                                        String.valueOf(body.get("text")).length())),
                                uuid(convId));
                    }
                    store.jdbc.update(
                            "update gxa_tasks set status='RUNNING',updated_at=now() where id=?",
                            uuid(selected));
                    store.event(
                            convId,
                            selected,
                            runId,
                            "run.accepted",
                            map("runId", runId, "taskId", selected));
                    return map(
                            "runId",
                            runId,
                            "taskId",
                            selected,
                            "messageId",
                            action ? null : messageId);
                });
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            var rows =
                    store.rows(
                            "update gxa_commands set status='RUNNING',updated_at=now() where id=(select id from gxa_commands where status='QUEUED' order by created_at,id for update skip locked limit 1) returning *");
            if (rows.isEmpty()) {
                busy.set(false);
                return;
            }
            executor.submit(
                    () -> {
                        try {
                            execute(rows.getFirst());
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (Exception e) {
            busy.set(false);
            log.error("Failed to claim queued command", e);
        }
    }

    private void execute(Map<String, Object> command) {
        String runId = command.get("id").toString(),
                taskId = command.get("task_id").toString(),
                conv = command.get("conversation_id").toString();
        AtomicReference<Map<String, Object>> latest = new AtomicReference<>();
        AtomicReference<String> responseId = new AtomicReference<>(UUID.randomUUID().toString());
        try {
            Actor actor = auth.actor(command.get("user_id").toString());
            latest.set(store.decode(store.ownedTask(taskId, actor).get("state")));
            if (store.rows(
                            "select token_hash from gxa_sessions where token_hash=? and expires_at>now()",
                            command.get("session_hash"))
                    .isEmpty()) throw new ApiException(401, "SESSION_EXPIRED", "登录已过期，请登录后恢复任务");
            var state = store.decode(store.ownedTask(taskId, actor).get("state"));
            latest.set(state);
            var payload = store.decode(command.get("payload"));
            var body = (Map<String, Object>) payload.get("body");
            boolean action = Boolean.TRUE.equals(payload.get("action"));
            ExecutionEvents events =
                    new ExecutionEvents() {
                        public boolean isCancellationRequested() {
                            return !store.rows(
                                            "select id from gxa_commands where task_id=? and status='QUEUED' and payload->'body'->>'type' in ('CANCEL','PAUSE') limit 1",
                                            uuid(taskId))
                                    .isEmpty();
                        }

                        public void checkpoint(Map<String, Object> s) {
                            latest.set(s);
                            store.saveState(taskId, s);
                            store.event(
                                    conv,
                                    taskId,
                                    runId,
                                    "task.updated",
                                    store.task(store.ownedTask(taskId, actor)));
                        }

                        public void emit(String type, Map<String, Object> p) {
                            if (type.equals("message.delta") && p.get("messageId") != null)
                                responseId.set(p.get("messageId").toString());
                            store.event(conv, taskId, runId, type, p);
                        }
                    };
            RunResult result =
                    runtime.run(
                            new RunRequest(
                                    actor,
                                    conv,
                                    runId,
                                    taskId,
                                    action ? "" : String.valueOf(body.get("text")),
                                    action ? body : Map.of(),
                                    state),
                            events);
            var finalState = new LinkedHashMap<>(result.state());
            finalState.put("status", result.status());
            finalState.put("components", result.components());
            String msgId = responseId.get();
            store.tx.executeWithoutResult(
                    tx -> {
                        store.saveState(taskId, finalState);
                        store.jdbc.update(
                                "insert into gxa_messages(id,conversation_id,run_id,role,content,components) values(?,?,?,?,?,?::jsonb) on conflict(id) do update set content=excluded.content,components=excluded.components",
                                uuid(msgId),
                                uuid(conv),
                                uuid(runId),
                                "ASSISTANT",
                                result.reply() == null ? "" : result.reply(),
                                store.encode(result.components()));
                        store.jdbc.update(
                                "update gxa_commands set status='COMPLETED',updated_at=now() where id=?",
                                uuid(runId));
                        store.event(
                                conv,
                                taskId,
                                runId,
                                "message.completed",
                                map("messageId", msgId, "content", result.reply()));
                        for (var c : result.components())
                            store.event(conv, taskId, runId, "component.upsert", c);
                        store.event(
                                conv,
                                taskId,
                                runId,
                                "task.updated",
                                store.task(store.ownedTask(taskId, actor)));
                        store.event(
                                conv,
                                taskId,
                                runId,
                                "run.finished",
                                map("runId", runId, "taskId", taskId, "status", result.status()));
                    });
        } catch (Exception e) {
            log.error("Agent run failed task={} run={}", taskId, runId, e);
            var state = latest.get();
            if (state == null) state = new LinkedHashMap<>();
            state.put("status", "FAILED_RECOVERABLE");
            state.put(
                    "lastError",
                    map(
                            "code",
                            e instanceof ApiException a ? a.code : "EXECUTION_ERROR",
                            "traceId",
                            runId));
            store.saveState(taskId, state);
            store.jdbc.update(
                    "update gxa_commands set status='FAILED',updated_at=now() where id=?",
                    uuid(runId));
            store.event(
                    conv,
                    taskId,
                    runId,
                    "error",
                    map(
                            "code",
                            e instanceof ApiException a ? a.code : "EXECUTION_ERROR",
                            "message",
                            e instanceof ApiException ? e.getMessage() : "本轮执行中断，进度已保存，可稍后恢复。",
                            "traceId",
                            runId,
                            "recoveryAction",
                            "RETRY_LATER"));
            store.event(
                    conv,
                    taskId,
                    runId,
                    "run.finished",
                    map("status", "FAILED_RECOVERABLE", "taskId", taskId));
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
        try {
            lock.close();
        } catch (Exception ignored) {
        }
    }
}
