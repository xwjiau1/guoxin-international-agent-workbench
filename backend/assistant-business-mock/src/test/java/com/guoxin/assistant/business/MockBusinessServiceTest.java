package com.guoxin.assistant.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against a disposable schema of a real PostgreSQL database, never H2 emulation. */
@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockBusinessServiceTest {
    private JdbcTemplate jdbc, admin;
    private DriverManagerDataSource ds;
    private MockBusinessService service;
    private String schema;
    private final Actor zhang = new Actor(MockBusinessService.ZHANG, "DEMO001", "张晨", "综合管理部"),
            li = new Actor(MockBusinessService.LI, "DEMO002", "李敏", "综合管理部");

    @BeforeAll
    void setup() {
        String url = System.getenv("PG_TEST_URL"),
                user = Objects.toString(System.getenv("PG_TEST_USER"), "guoxin"),
                password = Objects.toString(System.getenv("PG_TEST_PASSWORD"), "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "business_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        service = create("");
    }

    private MockBusinessService create(String fault) {
        return new MockBusinessService(
                jdbc,
                new DataSourceTransactionManager(ds),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneId.of("Asia/Shanghai")),
                fault);
    }

    @BeforeEach
    void clearReservations() {
        jdbc.update("DELETE FROM gx_biz_credential_reservation");
    }

    @AfterAll
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void onlyCurrentAssigneeAndDateRange() {
        ToolResult r =
                service.invoke(
                        "todo_search",
                        Map.of(
                                "types",
                                List.of("TRAVEL_APPLICATION"),
                                "from",
                                "2026-10-01",
                                "to",
                                "2026-11-01"),
                        zhang,
                        null);
        assertTrue(r.success());
        assertEquals(3L, r.data().get("totalCount"));
        ToolResult stats =
                service.invoke(
                        "todo_statistics",
                        Map.of(
                                "types",
                                List.of("TRAVEL_APPLICATION"),
                                "from",
                                "2026-10-01",
                                "to",
                                "2026-11-01"),
                        zhang,
                        null);
        assertEquals(3L, stats.data().get("totalCount"));
    }

    @Test
    void incompleteDraftAllowedButOwnershipEnforced() {
        ToolResult saved =
                service.invoke(
                        "draft_save",
                        Map.of("values", Map.of("destination", "日本")),
                        zhang,
                        UUID.randomUUID().toString());
        assertTrue(saved.success());
        String draft = saved.data().get("draftId").toString();
        assertFalse(service.invoke("draft_get", Map.of("draftId", draft), li, null).success());
        ToolResult validation =
                service.invoke(
                        "form_validate",
                        Map.of("values", Map.of("destination", "日本")),
                        zhang,
                        null);
        assertFalse(validation.success());
        assertTrue(validation.fieldErrors().containsKey("departureDate"));
    }

    @Test
    void commitResponseLostReconcilesAndReplayDoesNotDuplicate() {
        MockBusinessService faulty = create("COMMIT_THEN_TIMEOUT");
        String op = UUID.randomUUID().toString();
        Map<String, Object> a = Map.of("values", complete());
        ToolResult lost = faulty.invoke("submit_application", a, zhang, op);
        assertEquals("UNKNOWN_WRITE", lost.code());
        ToolResult receipt = faulty.invoke("operation_get", Map.of("operationId", op), zhang, null);
        assertEquals("SUCCEEDED", receipt.data().get("status"));
        ToolResult retry = faulty.invoke("submit_application", a, zhang, op);
        assertTrue(retry.success());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gx_biz_document WHERE id=?",
                        Integer.class,
                        retry.data().get("applicationId")));
        Map<String, Object> changed = new LinkedHashMap<>(complete());
        changed.put("destination", "韩国");
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                faulty.invoke("submit_application", Map.of("values", changed), zhang, op).code());
        assertFalse(
                faulty.invoke("operation_get", Map.of("operationId", op), li, null)
                        .data()
                        .get("status")
                        .equals("SUCCEEDED"));
    }

    @Test
    void approveUsesVersionAndNeverAnotherAssignee() {
        String id = "dddddddd-dddd-dddd-dddd-000000000012";
        Map<String, Object> a = Map.of("todoId", id, "expectedVersion", 1, "decision", "APPROVE");
        assertEquals(
                "NOT_FOUND",
                service.invoke("review_todo", a, zhang, UUID.randomUUID().toString()).code());
        assertEquals(
                "VERSION_CONFLICT",
                service.invoke(
                                "review_todo",
                                Map.of("todoId", id, "expectedVersion", 99, "decision", "APPROVE"),
                                li,
                                UUID.randomUUID().toString())
                        .code());
        String op = UUID.randomUUID().toString();
        assertTrue(service.invoke("review_todo", a, li, op).success());
        assertTrue(service.invoke("review_todo", a, li, op).success());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gx_biz_approval_record WHERE operation_id=?",
                        Integer.class,
                        op));
    }

    @Test
    void trustedIdentityAndCredentialOwnership() {
        Map<String, Object> v = new LinkedHashMap<>(complete());
        v.put("applicantId", li.id());
        v.put("name", "伪造姓名");
        ToolResult r = service.invoke("form_validate", Map.of("values", v), zhang, null);
        assertTrue(r.success());
        assertEquals(zhang.id(), ((Map<?, ?>) r.data().get("values")).get("applicantId"));
        assertEquals("张晨", ((Map<?, ?>) r.data().get("values")).get("name"));
        v.put("credentialId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
        assertFalse(service.invoke("form_validate", Map.of("values", v), zhang, null).success());
    }

    @Test
    void rejectMustHaveCommentAndWildcardDoesNotExpandSearch() {
        ToolResult r =
                service.invoke(
                        "review_todo",
                        Map.of(
                                "todoId",
                                "dddddddd-dddd-dddd-dddd-000000000005",
                                "expectedVersion",
                                1,
                                "decision",
                                "REJECT"),
                        zhang,
                        UUID.randomUUID().toString());
        assertTrue(r.fieldErrors().containsKey("comment"));
        assertEquals(
                0L,
                service.invoke("todo_search", Map.of("keyword", "%"), zhang, null)
                        .data()
                        .get("totalCount"));
    }

    @Test
    void staleDraftVersionCannotSaveOrSubmitAndSubmittedDraftCannotResubmit() {
        ToolResult draft =
                service.invoke(
                        "draft_save",
                        Map.of("values", complete()),
                        zhang,
                        UUID.randomUUID().toString());
        assertTrue(draft.success());
        String id = draft.data().get("draftId").toString();
        assertEquals(
                "VERSION_CONFLICT",
                service.invoke(
                                "draft_save",
                                Map.of("draftId", id, "values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
        ToolResult changed =
                service.invoke(
                        "draft_save",
                        Map.of("draftId", id, "expectedVersion", 1, "values", complete()),
                        zhang,
                        UUID.randomUUID().toString());
        assertTrue(changed.success());
        assertEquals(
                "VERSION_CONFLICT",
                service.invoke(
                                "submit_application",
                                Map.of(
                                        "draftId",
                                        id,
                                        "expectedDraftVersion",
                                        1,
                                        "values",
                                        complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
        assertTrue(
                service.invoke(
                                "submit_application",
                                Map.of(
                                        "draftId",
                                        id,
                                        "expectedDraftVersion",
                                        2,
                                        "values",
                                        complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .success());
        assertEquals(
                "STATE_CONFLICT",
                service.invoke(
                                "submit_application",
                                Map.of(
                                        "draftId",
                                        id,
                                        "expectedDraftVersion",
                                        3,
                                        "values",
                                        complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
        assertEquals(
                "STATE_CONFLICT",
                service.invoke(
                                "draft_save",
                                Map.of("draftId", id, "expectedVersion", 3, "values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
    }

    @Test
    void metadataAndScalarTypesMustMatch() {
        assertEquals(
                "SCHEMA_CHANGED",
                service.invoke(
                                "submit_application",
                                Map.of("metadataVersion", "obsolete", "values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
        Map<String, Object> v = new LinkedHashMap<>(complete());
        v.put("destination", Map.of("malformed", true));
        assertTrue(
                service.invoke("form_validate", Map.of("values", v), zhang, null)
                        .fieldErrors()
                        .containsKey("destination"));
    }

    @Test
    void correctedParametersUseNewOperationWhileFailedRequestRemainsReplayable() {
        String op = UUID.randomUUID().toString();
        Map<String, Object> missing = Map.of("values", Map.of("destination", "日本"));
        ToolResult first = service.invoke("submit_application", missing, zhang, op);
        assertEquals("VALIDATION_REQUIRED", first.code());
        assertEquals(first, service.invoke("submit_application", missing, zhang, op));
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                service.invoke("submit_application", Map.of("values", complete()), zhang, op)
                        .code());
        assertTrue(
                service.invoke(
                                "submit_application",
                                Map.of("values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .success());
    }

    @Test
    void borrowedAndReservedCredentialsCannotBeBorrowedAgainAndRejectionReleasesReservation() {
        Map<String, Object> borrowed = new LinkedHashMap<>(complete());
        borrowed.put("credentialId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        borrowed.put("credentialType", "HK_MACAO_PERMIT");
        assertFalse(
                service.invoke(
                                "submit_application",
                                Map.of("values", borrowed),
                                zhang,
                                UUID.randomUUID().toString())
                        .success());
        ToolResult submitted =
                service.invoke(
                        "submit_application",
                        Map.of("values", complete()),
                        zhang,
                        UUID.randomUUID().toString());
        assertTrue(submitted.success());
        assertEquals(
                "CREDENTIAL_RESERVED",
                service.invoke(
                                "submit_application",
                                Map.of("values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .code());
        String todo =
                jdbc.queryForObject(
                        "SELECT id FROM gx_biz_todo WHERE document_id=?",
                        String.class,
                        submitted.data().get("applicationId"));
        assertTrue(
                service.invoke(
                                "review_todo",
                                Map.of(
                                        "todoId",
                                        todo,
                                        "expectedVersion",
                                        1,
                                        "decision",
                                        "REJECT",
                                        "comment",
                                        "资料有误"),
                                li,
                                UUID.randomUUID().toString())
                        .success());
        assertTrue(
                service.invoke(
                                "submit_application",
                                Map.of("values", complete()),
                                zhang,
                                UUID.randomUUID().toString())
                        .success());
    }

    @Test
    void concurrentBorrowSubmissionsReserveExactlyOnce() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () ->
                                    service.invoke(
                                            "submit_application",
                                            Map.of("values", complete()),
                                            zhang,
                                            UUID.randomUUID().toString()));
            var second =
                    executor.submit(
                            () ->
                                    service.invoke(
                                            "submit_application",
                                            Map.of("values", complete()),
                                            zhang,
                                            UUID.randomUUID().toString()));
            var results =
                    List.of(
                            first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(ToolResult::success).count());
            assertEquals(
                    1,
                    results.stream().filter(x -> x.code().equals("CREDENTIAL_RESERVED")).count());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM gx_biz_credential_reservation", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> complete() {
        return Map.of(
                "requestMode",
                "BORROW",
                "credentialType",
                "PASSPORT",
                "credentialId",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1",
                "destination",
                "日本",
                "departureDate",
                "2026-12-15",
                "arrivalBackDate",
                "2026-12-20",
                "returnDate",
                "2026-12-21",
                "purpose",
                "旅游出行");
    }
}
