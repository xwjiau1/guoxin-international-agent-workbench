package com.guoxin.assistant.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.application.*;
import com.guoxin.assistant.domain.*;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** A small auditable graph. LLM preparation and deterministic authorized mutations are separate nodes. */
@Component
public class WorkflowAgentRuntime implements AgentRuntime {
    private final BusinessPort business;
    private final ObjectMapper json;
    private final QwenInterpreter interpreter;
    private final DataSource dataSource;

    public WorkflowAgentRuntime(
            BusinessPort business,
            ObjectMapper json,
            Environment env,
            ObjectProvider<DataSource> dataSources) {
        this.business = business;
        this.json = json;
        this.interpreter = new QwenInterpreter(env, json);
        this.dataSource = dataSources.getIfAvailable();
    }

    public RunResult run(RunRequest request, ExecutionEvents events) {
        Map<String, Object> state =
                new LinkedHashMap<>(request.state() == null ? Map.of() : request.state());
        if (Objects.equals(state.get("lastCompletedRunId"), request.runId()))
            return new RunResult(
                    state,
                    String.valueOf(state.getOrDefault("reply", "本轮已处理。")),
                    String.valueOf(state.getOrDefault("status", "WAITING_INPUT")),
                    components(state));
        state.put("taskId", request.taskId());
        state.put("modelCallCount", 0);
        state.put("readToolCallCount", 0);
        state.put("runDeadlineEpochMs", System.currentTimeMillis() + 180_000);
        final RunRequest execution =
                new RunRequest(
                        request.actor(),
                        request.conversationId(),
                        request.runId(),
                        request.taskId(),
                        request.text(),
                        request.action(),
                        state);
        Map<String, Object> incoming = request.action() == null ? Map.of() : request.action();
        Map<String, Object> incomingPayload = map(incoming.get("payload"));
        if (incomingPayload.containsKey("componentId")) {
            boolean valid =
                    components(state).stream()
                            .anyMatch(
                                    c ->
                                            Objects.equals(
                                                            c.get("componentId"),
                                                            incomingPayload.get("componentId"))
                                                    && Objects.equals(
                                                            String.valueOf(c.get("version")),
                                                            String.valueOf(
                                                                    incomingPayload.get(
                                                                            "componentVersion")))
                                                    && "ACTIVE".equals(c.get("status")));
            if (!valid)
                return new RunResult(
                        state,
                        "组件已失效，请刷新当前任务后操作。",
                        String.valueOf(state.getOrDefault("status", "WAITING_INPUT")),
                        components(state));
        }
        state.put("components", new ArrayList<>());
        try {
            var graph =
                    new StateGraph<AgentState>(AgentState::new)
                            .addNode(
                                    "load_context",
                                    node_async(
                                            gs -> {
                                                node(events, "load_context", "started");
                                                loadContext(execution, state, events);
                                                node(events, "load_context", "completed");
                                                events.checkpoint(state);
                                                return Map.of("stage", "CONTEXT_READY");
                                            }))
                            .addNode(
                                    "understand",
                                    node_async(
                                            gs -> {
                                                node(events, "understand", "started");
                                                understand(execution, state, events);
                                                node(events, "understand", "completed");
                                                events.checkpoint(state);
                                                return Map.of("stage", "UNDERSTOOD");
                                            }))
                            .addNode(
                                    "execute_workflow",
                                    node_async(
                                            gs -> {
                                                node(events, "execute_workflow", "started");
                                                execute(execution, state, events);
                                                state.put("lastCompletedRunId", request.runId());
                                                node(events, "execute_workflow", "completed");
                                                events.checkpoint(state);
                                                return Map.of(
                                                        "stage",
                                                        "FINISHED",
                                                        "taskSnapshot",
                                                        new LinkedHashMap<>(state));
                                            }))
                            .addEdge(StateGraph.START, "load_context")
                            .addEdge("load_context", "understand")
                            .addEdge("understand", "execute_workflow")
                            .addEdge("execute_workflow", StateGraph.END);
            var compile = CompileConfig.builder();
            if (dataSource != null)
                compile.checkpointSaver(
                        PostgresSaver.builder()
                                .datasource(dataSource)
                                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                                .createTables(true)
                                .build());
            var app = graph.compile(compile.build());
            app.invoke(
                    Map.of("taskId", request.taskId(), "runId", request.runId()),
                    RunnableConfig.builder()
                            .threadId(request.taskId() + ":" + request.runId())
                            .build());
            String baseline = String.valueOf(state.getOrDefault("reply", ""));
            if ((request.action() == null || request.action().isEmpty()) && !baseline.isBlank())
                state.put("reply", interpreter.summarize(request, state, events, baseline));
            remember(state, request.text(), String.valueOf(state.getOrDefault("reply", "")));
            return new RunResult(
                    state,
                    String.valueOf(state.getOrDefault("reply", "已完成本轮处理。")),
                    String.valueOf(state.getOrDefault("status", "WAITING_INPUT")),
                    components(state));
        } catch (Exception ex) {
            String status = state.containsKey("operationId") ? "RECONCILING" : "FAILED_RECOVERABLE";
            state.put("status", status);
            state.put(
                    "lastError",
                    Map.of(
                            "code",
                            "AGENT_EXECUTION_FAILED",
                            "errorType",
                            ex.getClass().getSimpleName()));
            state.put(
                    "reply",
                    ex.getMessage() != null && ex.getMessage().contains("MODEL_")
                            ? "模型服务暂时不可用，已保存当前任务，请稍后重试。"
                            : "执行暂时中断，任务与调用记录已保留，可恢复后继续核实。");
            addComponent(
                    state,
                    request,
                    "RECOVERY_NOTICE",
                    Map.of(
                            "errorCode",
                            "AGENT_EXECUTION_FAILED",
                            "recoveryAction",
                            status.equals("RECONCILING") ? "RECONCILE" : "RETRY_LATER"),
                    List.of("RESUME", "CANCEL"));
            events.emit(
                    "error",
                    Map.of(
                            "code",
                            "AGENT_EXECUTION_FAILED",
                            "errorType",
                            ex.getClass().getSimpleName(),
                            "message",
                            state.get("reply")));
            events.checkpoint(state);
            return new RunResult(state, (String) state.get("reply"), status, components(state));
        }
    }

    private void loadContext(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        s.putIfAbsent("values", new LinkedHashMap<>());
        s.putIfAbsent("filters", new LinkedHashMap<>());
        s.putIfAbsent("history", new ArrayList<>());
        if (r.action() != null && !r.action().isEmpty()) return;
        s.put(
                "capabilityCatalog",
                List.of(
                        Map.of("skillId", "passport-borrow", "description", "证照办理借用填单与草稿"),
                        Map.of("skillId", "todo-review", "description", "本人待办查询统计与确认代审核")));
        String text = r.text() == null ? "" : r.text();
        String kind =
                String.valueOf(
                        s.getOrDefault(
                                "kind",
                                text.contains("待办") || text.contains("报销")
                                        ? "TODO"
                                        : text.contains("护照") || text.contains("证照")
                                                ? "CREDENTIAL"
                                                : "CHAT"));
        if (Set.of("TODO", "CREDENTIAL").contains(kind)) loadSkill(s, kind, e);
        ToolResult profile = call("profile_get_self", Map.of(), r, e, null);
        if (profile.success()) s.put("profile", profile.data());
        ToolResult schema =
                call("form_get_schema", Map.of("formType", "PRIVATE_CREDENTIAL"), r, e, null);
        if (schema.success()) s.put("metadata", schema.data());
    }

    private void understand(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        if (r.action() != null && !r.action().isEmpty()) {
            s.put("command", r.action());
            return;
        }
        Map<String, Object> parsed =
                interpreter.interpret(r, s, e, (tool, args) -> call(tool, args, r, e, null));
        String kind = String.valueOf(parsed.getOrDefault("kind", s.getOrDefault("kind", "CHAT")));
        String priorKind = String.valueOf(s.getOrDefault("kind", "CHAT"));
        boolean completed =
                Set.of("SUCCEEDED", "CANCELLED", "FAILED_FINAL")
                        .contains(String.valueOf(s.get("status")));
        boolean newBusiness =
                Set.of("CREDENTIAL", "TODO").contains(kind)
                        && (!priorKind.equals(kind) || completed);
        if (newBusiness) {
            for (String key :
                    List.of(
                            "pendingConfirmation",
                            "operationId",
                            "draftId",
                            "draftVersion",
                            "draftOperationId",
                            "todoItems",
                            "selectedIds",
                            "decision",
                            "comment",
                            "values",
                            "filters",
                            "fieldSources")) s.remove(key);
            s.put("values", new LinkedHashMap<>());
            s.put("filters", new LinkedHashMap<>());
        }
        if (!kind.equals("CHAT") && !kind.equals("STATUS")) {
            s.put("kind", kind);
            loadSkill(s, kind, e);
        }
        s.put("interpretation", parsed);
        Map<String, Object> newValues = parse(parsed.get("valuesJson")),
                newFilters = parse(parsed.get("filtersJson"));
        newValues
                .keySet()
                .removeIf(
                        k ->
                                !Set.of(
                                                "requestMode",
                                                "credentialType",
                                                "credentialId",
                                                "destination",
                                                "departureDate",
                                                "arrivalBackDate",
                                                "returnDate",
                                                "purpose",
                                                "applicationReason",
                                                "remark")
                                        .contains(k));
        if (newValues.entrySet().stream()
                        .anyMatch(x -> !Objects.equals(values(s).get(x.getKey()), x.getValue()))
                || newFilters.entrySet().stream()
                        .anyMatch(x -> !Objects.equals(filters(s).get(x.getKey()), x.getValue())))
            s.remove("pendingConfirmation");
        values(s).putAll(newValues);
        filters(s).putAll(newFilters);
        Map<String, Object> sources =
                map(s.computeIfAbsent("fieldSources", k -> new LinkedHashMap<>()));
        for (String key : newValues.keySet())
            sources.put(
                    key,
                    Map.of(
                            "source",
                            "USER",
                            "sourceRef",
                            r.runId(),
                            "updatedAt",
                            Instant.now().toString(),
                            "dateReference",
                            LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),
                            "timezone",
                            "Asia/Shanghai"));
        if (!"NONE".equals(parsed.get("decision")) && parsed.containsKey("decision")) {
            if (!Objects.equals(s.get("decision"), parsed.get("decision")))
                s.remove("pendingConfirmation");
            s.put("decision", parsed.get("decision"));
        }
        if (parsed.get("comment") != null && !String.valueOf(parsed.get("comment")).isBlank()) {
            if (!Objects.equals(s.get("comment"), parsed.get("comment")))
                s.remove("pendingConfirmation");
            s.put("comment", parsed.get("comment"));
        }
        s.put("reply", parsed.getOrDefault("reply", ""));
        s.put(
                "command",
                Map.of("type", parsed.getOrDefault("nextAction", "CONTINUE"), "payload", Map.of()));
        if (kind.equals("STATUS")) s.put("queryStatus", true);
    }

    private void execute(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        if (e.isCancellationRequested() && !s.containsKey("operationId")) {
            finish(s, "CANCELLED", "已停止后续处理。尚未发出的业务请求不会执行，草稿仍保留。");
            return;
        }
        Map<String, Object> command = map(s.get("command")), payload = map(command.get("payload"));
        String type = String.valueOf(command.getOrDefault("type", "CONTINUE"));
        if (type.equals("CANCEL") || type.equals("PAUSE")) {
            finish(
                    s,
                    type.equals("CANCEL") ? "CANCELLED" : "PAUSED",
                    "已" + (type.equals("CANCEL") ? "取消本次任务。已保存的草稿仍保留。" : "暂停任务，之后可以继续。"));
            return;
        }
        if (type.equals("PROVIDE_FIELDS")) {
            Map<String, Object> patch = new LinkedHashMap<>(map(payload.get("values")));
            patch.keySet()
                    .removeIf(
                            k ->
                                    !Set.of(
                                                    "requestMode",
                                                    "credentialType",
                                                    "credentialId",
                                                    "destination",
                                                    "departureDate",
                                                    "arrivalBackDate",
                                                    "returnDate",
                                                    "purpose",
                                                    "applicationReason",
                                                    "remark")
                                            .contains(k));
            values(s).putAll(patch);
            s.remove("pendingConfirmation");
            Map<String, Object> sources =
                    map(s.computeIfAbsent("fieldSources", k -> new LinkedHashMap<>()));
            for (String key : patch.keySet())
                sources.put(
                        key,
                        Map.of(
                                "source",
                                "USER",
                                "sourceRef",
                                r.runId(),
                                "updatedAt",
                                Instant.now().toString(),
                                "timezone",
                                "Asia/Shanghai"));
        }
        if (type.equals("UPDATE_REVIEW")) {
            List<String> selected =
                    list(payload.get("selectedTodoIds")).stream().map(String::valueOf).toList();
            var items = list(s.get("todoItems"));
            Set<String> allowed = new HashSet<>();
            for (Object item : items) allowed.add(itemId(map(item)));
            if (selected.isEmpty() || !allowed.containsAll(selected) || selected.size() > 100) {
                finish(s, "WAITING_INPUT", "请选择当前列表中的待办，单次最多100项。");
                return;
            }
            s.put("selectedIds", selected);
            s.put("decision", payload.getOrDefault("decision", "APPROVE"));
            s.put("comment", payload.getOrDefault("comment", ""));
            s.remove("pendingConfirmation");
            todoPreview(r, s, e);
            return;
        }
        if (type.equals("CONFIRM")) {
            confirm(r, s, e, payload);
            return;
        }
        if (type.equals("RETRY_FAILED") || type.equals("RESUME")) {
            if (s.get("pendingConfirmation") instanceof Map<?, ?> p
                    && p.get("operationId") != null) {
                confirm(r, s, e, Map.of("confirmationId", p.get("id")));
                return;
            }
        }
        if (Boolean.TRUE.equals(s.remove("queryStatus")) && s.get("applicationId") != null) {
            ToolResult result =
                    call(
                            "application_get",
                            Map.of("applicationId", s.get("applicationId")),
                            r,
                            e,
                            null);
            addComponent(s, r, "BUSINESS_RECEIPT", result.data(), List.of());
            finish(s, "SUCCEEDED", result.success() ? "已查询最新审批状态，请查看业务回执。" : result.message());
            return;
        }
        if (type.equals("SAVE_DRAFT")) {
            saveDraft(r, s, e);
            return;
        }
        switch (String.valueOf(s.getOrDefault("kind", "CHAT"))) {
            case "CREDENTIAL" -> credential(r, s, e);
            case "TODO" -> todos(r, s, e);
            default ->
                    finish(
                            s,
                            "WAITING_INPUT",
                            String.valueOf(s.getOrDefault("reply", "你好，我可以帮你办理证照申请、查询和审核本人待办。")));
        }
    }

    private void credential(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        Map<String, Object> v = values(s);
        Map<String, Object> profile = map(s.get("profile"));
        Map<String, Object> sources =
                map(s.computeIfAbsent("fieldSources", k -> new LinkedHashMap<>()));
        for (String k : List.of("applicantId", "name", "department"))
            if (profile.containsKey(k)) {
                v.put(k, profile.get(k));
                sources.put(
                        k,
                        Map.of(
                                "source",
                                "PROFILE",
                                "sourceRef",
                                r.actor().id(),
                                "updatedAt",
                                Instant.now().toString(),
                                "editable",
                                false));
            }
        if (!v.containsKey("credentialId") && "BORROW".equals(v.get("requestMode"))) {
            ToolResult cert =
                    call(
                            "credential_list_mine",
                            v.containsKey("credentialType")
                                    ? Map.of("credentialType", v.get("credentialType"))
                                    : Map.of(),
                            r,
                            e,
                            null);
            List<Object> items = list(cert.data().get("items"));
            if (items.size() == 1) {
                Map<String, Object> item = map(items.getFirst());
                v.put(
                        "credentialId",
                        item.getOrDefault("id", item.getOrDefault("credentialId", "")));
            }
            s.put("credentials", items);
        }
        ToolResult validation = call("form_validate", formArgs(s, "SUBMIT"), r, e, null);
        if (!validation.success()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemaVersion", metadataVersion(s));
            data.put("fields", metadataFields(s));
            data.put("values", v);
            data.put("fieldErrors", validation.fieldErrors());
            data.put("credentials", s.getOrDefault("credentials", List.of()));
            addComponent(
                    s,
                    r,
                    "FIELD_COLLECTION",
                    data,
                    List.of("PROVIDE_FIELDS", "SAVE_DRAFT", "PAUSE"));
            String missing = String.join("、", validation.fieldErrors().values());
            finish(
                    s,
                    "WAITING_INPUT",
                    missing.isBlank() ? validation.message() : "请补充或修正：" + missing);
            return;
        }
        Map<String, Object> pending = newConfirmation(s, "SUBMIT", formArgs(s, "SUBMIT"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("formType", "PRIVATE_CREDENTIAL");
        d.put("metadataVersion", metadataVersion(s));
        d.put("values", v);
        d.put("fields", metadataFields(s));
        d.put("confirmationId", pending.get("id"));
        d.put("expiresAt", pending.get("expiresAt"));
        d.put("credentials", s.getOrDefault("credentials", List.of()));
        addComponent(s, r, "FORM_PREVIEW", d, List.of("PROVIDE_FIELDS", "SAVE_DRAFT", "CONFIRM"));
        finish(s, "WAITING_CONFIRMATION", "资料已补齐，请核对申请内容。确认后将生成单据并提交审批。");
    }

    private void saveDraft(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        if (!"CREDENTIAL".equals(s.get("kind"))) {
            finish(s, "WAITING_INPUT", "当前不是填单任务，暂无可保存的单据草稿。");
            return;
        }
        Map<String, Object> args = formArgs(s, "DRAFT");
        if (s.containsKey("draftId")) args.put("draftId", s.get("draftId"));
        if (s.containsKey("draftVersion")) args.put("expectedVersion", s.get("draftVersion"));
        String op =
                String.valueOf(
                        s.computeIfAbsent("draftOperationId", k -> UUID.randomUUID().toString()));
        e.checkpoint(s);
        ToolResult result = call("draft_save", args, r, e, op);
        if (result.success()) {
            s.put("draftId", result.data().get("draftId"));
            s.put("draftVersion", result.data().get("version"));
            s.remove("draftOperationId");
            s.remove("pendingConfirmation");
            addComponent(s, r, "BUSINESS_RECEIPT", result.data(), List.of("RESUME"));
            finish(s, "DRAFT_SAVED", "草稿已保存，尚未提交审批。你可以继续补充或修改。");
        } else {
            if (!isTransient(result)) s.remove("draftOperationId");
            finish(s, "WAITING_INPUT", result.message());
        }
    }

    private void todos(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        Map<String, Object> f = filters(s);
        f.putIfAbsent("status", "PENDING");
        f.putIfAbsent("dateField", "arrivedAt");
        f.put("limit", 100);
        ToolResult results = call("todo_search", f, r, e, null);
        if (!results.success()) {
            finish(s, "FAILED_RECOVERABLE", results.message());
            return;
        }
        List<Object> items = list(results.data().get("items"));
        s.put("todoItems", items);
        s.put("selectedIds", items.stream().map(i -> itemId(map(i))).toList());
        ToolResult stats = call("todo_statistics", f, r, e, null);
        s.put("statistics", stats.data());
        if (items.isEmpty()) {
            finish(s, "WAITING_INPUT", "没有找到符合条件且当前由你处理的待办，可以调整类型、日期或关键词。");
            return;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("filters", f);
        d.put("items", items);
        d.put("totalCount", results.data().getOrDefault("totalCount", items.size()));
        d.put("selectedIds", s.get("selectedIds"));
        d.put("decision", s.getOrDefault("decision", "APPROVE"));
        d.put("comment", s.getOrDefault("comment", ""));
        d.put("statistics", stats.data());
        addComponent(s, r, "TODO_SELECTION", d, List.of("UPDATE_REVIEW"));
        finish(s, "WAITING_INPUT", "已查询本人待办。请在组件中选择范围、审核动作和意见，再生成审核预览。");
        if (s.containsKey("decision")) todoPreview(r, s, e);
    }

    private void todoPreview(RunRequest r, Map<String, Object> s, ExecutionEvents e) {
        String decision = String.valueOf(s.getOrDefault("decision", "APPROVE")),
                comment = String.valueOf(s.getOrDefault("comment", ""));
        if (!Set.of("APPROVE", "REJECT").contains(decision)) {
            finish(s, "WAITING_INPUT", "请选择同意或驳回。");
            return;
        }
        if (decision.equals("REJECT") && comment.strip().length() < 2) {
            finish(s, "WAITING_INPUT", "请补充驳回原因，再确认审核范围。");
            return;
        }
        Set<String> selected =
                new HashSet<>(list(s.get("selectedIds")).stream().map(String::valueOf).toList());
        List<Object> items =
                list(s.get("todoItems")).stream()
                        .filter(i -> selected.contains(itemId(map(i))))
                        .toList();
        if (items.isEmpty()) {
            finish(s, "WAITING_INPUT", "请至少选择一条待办。");
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("items", items);
        args.put("decision", decision);
        args.put("comment", comment);
        Map<String, Object> p = newConfirmation(s, "REVIEW", args);
        addComponent(
                s,
                r,
                "APPROVAL_PREVIEW",
                Map.of(
                        "items",
                        items,
                        "decision",
                        decision,
                        "comment",
                        comment,
                        "confirmationId",
                        p.get("id"),
                        "expiresAt",
                        p.get("expiresAt"),
                        "snapshotId",
                        p.get("id")),
                List.of("UPDATE_REVIEW", "CONFIRM"));
        finish(
                s,
                "WAITING_CONFIRMATION",
                "请确认对选中的 "
                        + items.size()
                        + " 条待办执行"
                        + (decision.equals("REJECT") ? "驳回" : "同意")
                        + "。确认前不会提交审核。");
    }

    private void confirm(
            RunRequest r, Map<String, Object> s, ExecutionEvents e, Map<String, Object> payload) {
        Map<String, Object> p = map(s.get("pendingConfirmation"));
        if (p.isEmpty()
                || (!payload.isEmpty()
                        && !Objects.equals(payload.get("confirmationId"), p.get("id")))) {
            finish(s, "WAITING_INPUT", "没有匹配的待确认操作，请重新生成预览。");
            return;
        }
        if (!p.containsKey("operationId")
                && Instant.parse(String.valueOf(p.get("expiresAt"))).isBefore(Instant.now())) {
            s.remove("pendingConfirmation");
            finish(s, "WAITING_INPUT", "确认已过期，请重新核对办理内容。");
            return;
        }
        if (Boolean.TRUE.equals(p.get("completed"))) {
            addComponent(s, r, "BUSINESS_RECEIPT", map(p.get("result")), List.of());
            finish(s, "SUCCEEDED", "该操作已完成，返回原业务回执，未重复办理。");
            return;
        }
        if (e.isCancellationRequested()
                && p.containsKey("operationId")
                && p.get("kind").equals("SUBMIT")) {
            ToolResult receipt =
                    call("operation_get", Map.of("operationId", p.get("operationId")), r, e, null);
            if (receipt.success() && "SUCCEEDED".equals(receipt.data().get("status"))) {
                Map<String, Object> data = map(receipt.data().get("data"));
                p.put("completed", true);
                p.put("result", data);
                addComponent(s, r, "BUSINESS_RECEIPT", data, List.of());
                finish(s, "SUCCEEDED", "已停止后续处理；原提交经核实已成功，请查看真实回执。");
            } else finish(s, "RECONCILING", "已停止新的提交；先前操作结果仍待核实，不会自动重复办理。");
            return;
        }
        String op =
                String.valueOf(p.computeIfAbsent("operationId", k -> UUID.randomUUID().toString()));
        s.put("operationId", op);
        s.put("status", "EXECUTING");
        e.checkpoint(s);
        Map<String, Object> args = new LinkedHashMap<>(map(p.get("payload")));
        if (p.get("kind").equals("SUBMIT")) {
            if (s.containsKey("draftId")) {
                args.put("draftId", s.get("draftId"));
                args.put("expectedDraftVersion", s.getOrDefault("draftVersion", 0));
            }
            ToolResult result = call("submit_application", args, r, e, op);
            if (result.success()) {
                s.put("applicationId", result.data().get("applicationId"));
                p.put("completed", true);
                p.put("result", result.data());
                addComponent(s, r, "BUSINESS_RECEIPT", result.data(), List.of());
                finish(s, "SUCCEEDED", "申请已提交审批。请查看单据号和当前流程状态；提交不代表审批通过。");
            } else handleWriteFailure(r, s, e, result);
        } else {
            List<Map<String, Object>> results = new ArrayList<>();
            int success = 0;
            boolean retryable = false, cancelled = false;
            Map<String, Object> completed =
                    map(p.computeIfAbsent("itemResults", k -> new LinkedHashMap<>()));
            for (Object o : list(args.get("items"))) {
                Map<String, Object> item = map(o);
                String itemId = itemId(item);
                Map<String, Object> previous = map(completed.get(itemId));
                if (Boolean.TRUE.equals(previous.get("success"))) {
                    results.add(previous);
                    success++;
                    continue;
                }
                if (e.isCancellationRequested()) {
                    cancelled = true;
                    if ("UNKNOWN_WRITE".equals(previous.get("code"))) {
                        results.add(previous);
                        retryable = true;
                    } else
                        results.add(
                                Map.of(
                                        "todoId",
                                        itemId,
                                        "success",
                                        false,
                                        "code",
                                        "SKIPPED",
                                        "message",
                                        "用户要求停止，未发起该项审核"));
                    continue;
                }
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("todoId", itemId);
                a.put("expectedVersion", item.getOrDefault("version", 0));
                a.put("decision", args.get("decision"));
                a.put("comment", args.getOrDefault("comment", ""));
                ToolResult result =
                        call(
                                "review_todo",
                                a,
                                r,
                                e,
                                UUID.nameUUIDFromBytes(
                                                (op + ":" + itemId)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))
                                        .toString());
                Map<String, Object> row = new LinkedHashMap<>(result.data());
                row.put("todoId", itemId);
                row.put("success", result.success());
                row.put("code", result.code());
                row.put("message", result.message());
                results.add(row);
                completed.put(itemId, row);
                if (result.success()) success++;
                else if (isTransient(result)) retryable = true;
                e.checkpoint(s);
            }
            boolean unknown =
                    results.stream().anyMatch(item -> "UNKNOWN_WRITE".equals(item.get("code")));
            String overall =
                    unknown
                            ? "RECONCILING"
                            : cancelled
                                    ? (success > 0 ? "PARTIAL_SUCCEEDED" : "CANCELLED")
                                    : success == results.size() ? "SUCCEEDED" : "PARTIAL_SUCCEEDED";
            Map<String, Object> receipt =
                    Map.of(
                            "operationId",
                            op,
                            "items",
                            results,
                            "successCount",
                            success,
                            "failedCount",
                            results.size() - success,
                            "status",
                            overall);
            p.put("completed", !retryable);
            p.put("result", receipt);
            addComponent(
                    s,
                    r,
                    "BUSINESS_RECEIPT",
                    receipt,
                    retryable ? List.of("RETRY_FAILED") : List.of());
            finish(
                    s,
                    overall,
                    cancelled
                            ? "已停止后续审核，已成功处理 " + success + " 条，其余未执行、失败或待核实情况见回执。"
                            : "审核处理结束：成功 "
                                    + success
                                    + " 条，失败或待核实 "
                                    + (results.size() - success)
                                    + " 条。详情见逐项回执。");
        }
    }

    private void handleWriteFailure(
            RunRequest r, Map<String, Object> s, ExecutionEvents e, ToolResult result) {
        if ("CONTROL".equals(result.category())) {
            finish(s, "CANCELLED", result.message());
            return;
        }
        if (!result.fieldErrors().isEmpty()) {
            s.remove("pendingConfirmation");
            s.remove("operationId");
            credential(r, s, e);
            return;
        }
        String status = isTransient(result) ? "RECONCILING" : "WAITING_INPUT";
        finish(s, status, result.message());
        if (!isTransient(result)) s.remove("pendingConfirmation");
        addComponent(
                s,
                r,
                "RECOVERY_NOTICE",
                Map.of(
                        "errorCode",
                        result.code(),
                        "reason",
                        result.message(),
                        "recoveryAction",
                        isTransient(result) ? "RECONCILE" : "REFRESH_PREVIEW"),
                List.of("RESUME"));
    }

    private ToolResult call(
            String tool, Map<String, Object> args, RunRequest r, ExecutionEvents e, String op) {
        String callId = UUID.randomUUID().toString();
        boolean writeSent = false;
        if (op == null && !tool.equals("operation_get")) {
            int count = ((Number) r.state().getOrDefault("readToolCallCount", 0)).intValue();
            if (count >= 20)
                return ToolResult.error("TOOL_BUDGET_EXHAUSTED", "INTERNAL", "本轮读取工具调用预算已耗尽");
            r.state().put("readToolCallCount", count + 1);
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (e.isCancellationRequested() && !tool.equals("operation_get"))
                return ToolResult.error(
                        writeSent ? "UNKNOWN_WRITE" : "CANCELLED",
                        writeSent ? "UNKNOWN_WRITE" : "CONTROL",
                        writeSent ? "已停止重试，正在保留并核实已发出的操作结果" : "已停止后续处理，未发起新的业务操作");
            if (System.currentTimeMillis()
                    > ((Number) r.state().getOrDefault("runDeadlineEpochMs", Long.MAX_VALUE))
                            .longValue())
                return ToolResult.error("TIME_BUDGET_EXHAUSTED", "INTERNAL", "本轮自动执行时间预算已耗尽，任务已保留");
            long start = System.nanoTime();
            e.emit(
                    "tool.started",
                    Map.of(
                            "tool",
                            tool,
                            "toolCallId",
                            callId,
                            "attempt",
                            attempt,
                            "arguments",
                            args,
                            "operationId",
                            op == null ? "" : op));
            ToolResult result;
            try {
                if (op != null) writeSent = true;
                result = business.invoke(tool, args, r.actor(), op);
            } catch (IllegalArgumentException ex) {
                result = ToolResult.error("INVALID_ARGUMENT", "VALIDATION", "参数不符合接口要求，请检查输入");
            } catch (SecurityException ex) {
                result = ToolResult.error("FORBIDDEN", "AUTH", "无权执行该操作");
            } catch (Exception ex) {
                Throwable cause = ex;
                while (cause.getCause() != null) cause = cause.getCause();
                String sqlState =
                        cause instanceof java.sql.SQLException sql
                                ? Objects.toString(sql.getSQLState(), "")
                                : "";
                boolean network =
                        cause instanceof java.io.IOException
                                || cause instanceof java.net.http.HttpTimeoutException
                                || cause instanceof java.sql.SQLTransientException
                                || sqlState.startsWith("08")
                                || Set.of("40001", "40P01").contains(sqlState);
                result =
                        ToolResult.error(
                                network ? "NETWORK_ERROR" : "INTERNAL_ERROR",
                                network ? "TRANSIENT" : "INTERNAL",
                                network ? "接口暂时不可用，请稍后重试" : "接口发生内部异常，已保存任务，请查看执行记录");
            }
            e.emit(
                    "tool.completed",
                    Map.of(
                            "tool",
                            tool,
                            "toolCallId",
                            callId,
                            "attempt",
                            attempt,
                            "code",
                            result.code(),
                            "success",
                            result.success(),
                            "durationMs",
                            (System.nanoTime() - start) / 1_000_000,
                            "result",
                            result));
            if (result.success() || !isTransient(result)) return result;
            if (op != null && !tool.equals("operation_get")) {
                ToolResult check = call("operation_get", Map.of("operationId", op), r, e, null);
                e.emit(
                        "operation.updated",
                        Map.of("operationId", op, "code", check.code(), "result", check));
                if (check.success() && "SUCCEEDED".equals(check.data().get("status")))
                    return ToolResult.ok(map(check.data().getOrDefault("data", check.data())));
            }
            if (attempt == 3) return result;
            long indicated = 0;
            try {
                indicated =
                        Long.parseLong(
                                String.valueOf(result.data().getOrDefault("retryAfterMs", 0)));
            } catch (NumberFormatException ignored) {
            }
            long delay =
                    Math.max(
                            indicated,
                            ThreadLocalRandom.current()
                                    .nextLong(0, Math.min(5000, 500L * (1L << (attempt - 1))) + 1));
            if (delay > 10_000) return result;
            e.emit(
                    "retry.scheduled",
                    Map.of("tool", tool, "attempt", attempt + 1, "delayMs", delay));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ToolResult.error("CANCELLED", "INTERNAL", "任务已中断");
            }
        }
        return ToolResult.error("RETRY_EXHAUSTED", "TRANSIENT", "重试次数已耗尽");
    }

    private static boolean isTransient(ToolResult r) {
        return Set.of("TRANSIENT", "RATE_LIMIT", "NETWORK", "UNKNOWN_WRITE").contains(r.category());
    }

    private void loadSkill(Map<String, Object> s, String kind, ExecutionEvents e) {
        String id = kind.equals("TODO") ? "todo-review" : "passport-borrow";
        s.put("skillId", id);
        s.put("skillVersion", "1.0.0");
        String body = "";
        for (Path path :
                List.of(
                        Path.of("capabilities", id, "1.0.0", "instructions.md"),
                        Path.of("..", "capabilities", id, "1.0.0", "instructions.md"))) {
            try {
                if (Files.exists(path)) {
                    body = Files.readString(path);
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        s.put("skillInstructions", body);
        e.emit(
                "skill.loaded",
                Map.of("skillId", id, "version", "1.0.0", "loaded", !body.isEmpty()));
    }

    private Map<String, Object> newConfirmation(
            Map<String, Object> s, String kind, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", UUID.randomUUID().toString());
        p.put("kind", kind);
        p.put("payload", deepCopy(payload));
        p.put("expiresAt", Instant.now().plusSeconds(900).toString());
        s.put("pendingConfirmation", p);
        return p;
    }

    private Map<String, Object> formArgs(Map<String, Object> s, String mode) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("formType", "PRIVATE_CREDENTIAL");
        a.put("metadataVersion", metadataVersion(s));
        a.put("mode", mode);
        a.put("values", new LinkedHashMap<>(values(s)));
        return a;
    }

    private String metadataVersion(Map<String, Object> s) {
        return String.valueOf(map(s.get("metadata")).getOrDefault("version", "1.0.0"));
    }

    private Object metadataFields(Map<String, Object> s) {
        return map(s.get("metadata")).getOrDefault("fields", List.of());
    }

    private void addComponent(
            Map<String, Object> s,
            RunRequest r,
            String type,
            Map<String, Object> data,
            List<String> actions) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("componentId", UUID.randomUUID().toString());
        c.put("taskId", r.taskId());
        c.put("type", type);
        c.put("version", 1);
        c.put("status", "ACTIVE");
        c.put("data", deepCopy(data));
        c.put("allowedActions", actions);
        components(s).add(c);
    }

    private void remember(Map<String, Object> s, String user, String reply) {
        List<Object> h = list(s.get("history"));
        if (user != null && !user.isBlank()) h.add(Map.of("role", "user", "text", user));
        h.add(Map.of("role", "assistant", "text", reply));
        if (h.size() > 12) {
            List<Object> preferences = new ArrayList<>(list(s.get("archivedUserIntents")));
            for (Object prior : h.subList(0, h.size() - 12)) {
                Map<String, Object> m = map(prior);
                if ("user".equals(m.get("role"))) {
                    String text = String.valueOf(m.get("text"));
                    preferences.add(text.length() > 500 ? text.substring(0, 500) : text);
                }
            }
            if (preferences.size() > 20)
                preferences =
                        new ArrayList<>(
                                preferences.subList(preferences.size() - 20, preferences.size()));
            s.put("archivedUserIntents", preferences);
            s.put(
                    "historySummary",
                    Map.of(
                            "taskType",
                            s.getOrDefault("kind", "CHAT"),
                            "previousUserRequests",
                            preferences,
                            "currentValues",
                            new LinkedHashMap<>(values(s)),
                            "note",
                            "旧表达仅供上下文参考，执行事实以结构化状态与业务回执为准"));
            s.put("history", new ArrayList<>(h.subList(h.size() - 12, h.size())));
        } else s.put("history", h);
    }

    private static void node(ExecutionEvents e, String n, String status) {
        e.emit("node." + status, Map.of("node", n));
    }

    private static void finish(Map<String, Object> s, String status, String reply) {
        s.put("status", status);
        s.put("reply", reply);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> ? (List<Object>) o : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> components(Map<String, Object> s) {
        return (List<Map<String, Object>>) s.computeIfAbsent("components", k -> new ArrayList<>());
    }

    private static Map<String, Object> values(Map<String, Object> s) {
        return map(s.computeIfAbsent("values", k -> new LinkedHashMap<>()));
    }

    private static Map<String, Object> filters(Map<String, Object> s) {
        return map(s.computeIfAbsent("filters", k -> new LinkedHashMap<>()));
    }

    private static String itemId(Map<String, Object> i) {
        return String.valueOf(i.getOrDefault("id", i.getOrDefault("todoId", "")));
    }

    private Map<String, Object> parse(Object value) {
        try {
            return json.readValue(
                    String.valueOf(value == null ? "{}" : value), new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("MODEL_ARGUMENT_INVALID", ex);
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> o) {
        return json.convertValue(o, new TypeReference<>() {});
    }
}
