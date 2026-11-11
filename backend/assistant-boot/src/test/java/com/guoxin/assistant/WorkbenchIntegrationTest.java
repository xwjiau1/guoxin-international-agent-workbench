package com.guoxin.assistant;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Runs against an actual PostgreSQL supplied by the Compose verify service. */
@SpringBootTest(properties={"agent.model-mode=test","spring.profiles.active=test"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="PG_TEST_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchIntegrationTest {
 static final String SCHEMA="boot_test_"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception{
  String url=System.getenv("PG_TEST_URL"),user=System.getenv().getOrDefault("PG_TEST_USER","guoxin"),password=System.getenv().getOrDefault("PG_TEST_PASSWORD","");
  try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()){s.execute("CREATE SCHEMA "+SCHEMA);}
  r.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA);r.add("spring.datasource.username",()->user);r.add("spring.datasource.password",()->password);
 }
 @Autowired MockMvc http;@Autowired ObjectMapper json;
 record Login(Cookie cookie,String csrf){}
 Login login(String username)throws Exception{
  // The test profile intentionally omits demo-login. Insert test sessions without enabling a production login route.
  String actor=username.equals("DEMO001")?"11111111-1111-1111-1111-111111111111":"22222222-2222-2222-2222-222222222222";
  String token=UUID.randomUUID().toString(),csrf=UUID.randomUUID().toString();
  String url=System.getenv("PG_TEST_URL")+(System.getenv("PG_TEST_URL").contains("?")?"&":"?")+"currentSchema="+SCHEMA;
  try(Connection c=DriverManager.getConnection(url,System.getenv().getOrDefault("PG_TEST_USER","guoxin"),System.getenv().getOrDefault("PG_TEST_PASSWORD",""));PreparedStatement s=c.prepareStatement("insert into gxa_sessions(token_hash,user_id,csrf_token,expires_at) values(?,?::uuid,?,now()+interval '1 hour')")){s.setString(1,com.guoxin.assistant.infrastructure.AgentStore.hash(token));s.setString(2,actor);s.setString(3,csrf);s.executeUpdate();}
  return new Login(new Cookie("GX_SESSION",token),csrf);
 }
 JsonNode data(MvcResult r)throws Exception{assertTrue(r.getResponse().getStatus()<300,r.getResponse().getContentAsString());return json.readTree(r.getResponse().getContentAsString()).get("data");}
 JsonNode create(Login l)throws Exception{return data(http.perform(post("/api/v1/conversations").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content("{}")).andReturn());}
 JsonNode task(Login l,String id)throws Exception{return data(http.perform(get("/api/v1/tasks/"+id).cookie(l.cookie())).andReturn());}
 JsonNode waitTask(Login l,String id)throws Exception{for(int i=0;i<200;i++){var t=task(l,id);if(!t.path("status").asText().equals("RUNNING")&&t.path("activeRunId").isNull())return t;Thread.sleep(50);}fail("Agent command did not finish");return null;}
 @Test @Order(1) void authenticationAndCsrfAreEnforced()throws Exception{
  assertEquals(401,http.perform(get("/api/v1/me")).andReturn().getResponse().getStatus());var l=login("DEMO001");assertEquals(403,http.perform(post("/api/v1/conversations").cookie(l.cookie()).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());assertEquals("张晨",data(http.perform(get("/api/v1/me").cookie(l.cookie())).andReturn()).get("name").asText());
 }
 @Test @Order(2) void messageDedupeAndOwnership()throws Exception{
  var l=login("DEMO001");String conv=create(l).get("id").asText(),client=UUID.randomUUID().toString();String body=json.writeValueAsString(Map.of("clientMessageId",client,"text","帮我借护照去日本旅游"));
  var a=data(http.perform(post("/api/v1/conversations/"+conv+"/messages").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content(body)).andReturn());var b=data(http.perform(post("/api/v1/conversations/"+conv+"/messages").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content(body)).andReturn());assertEquals(a.get("runId"),b.get("runId"));String taskId=a.get("taskId").asText();assertEquals("WAITING_INPUT",waitTask(l,taskId).get("status").asText());
  var other=login("DEMO002");assertEquals(404,http.perform(get("/api/v1/tasks/"+taskId).cookie(other.cookie())).andReturn().getResponse().getStatus());
  assertEquals(409,http.perform(post("/api/v1/conversations/"+conv+"/messages").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("clientMessageId",client,"text","different")))).andReturn().getResponse().getStatus());
 }
 @Test @Order(3) void incompleteDraftPersistsAndReturnsTaskAssociation()throws Exception{
  var l=login("DEMO001");String conv=create(l).get("id").asText();var accepted=data(http.perform(post("/api/v1/conversations/"+conv+"/messages").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("clientMessageId",UUID.randomUUID().toString(),"text","帮我借护照去日本旅游")))).andReturn());String id=accepted.get("taskId").asText();var before=waitTask(l,id);
  var body=Map.of("actionId",UUID.randomUUID().toString(),"type","SAVE_DRAFT","expectedTaskVersion",before.get("version").asLong(),"payload",Map.of());
  data(http.perform(post("/api/v1/tasks/"+id+"/actions").cookie(l.cookie()).header("X-CSRF-Token",l.csrf()).contentType("application/json").content(json.writeValueAsString(body))).andReturn());var after=waitTask(l,id);assertEquals("DRAFT_SAVED",after.get("status").asText());assertFalse(after.get("draftId").isNull());
  var draft=data(http.perform(get("/api/v1/drafts/"+after.get("draftId").asText()).cookie(l.cookie())).andReturn());assertEquals(id,draft.get("taskId").asText());assertEquals(conv,draft.get("conversationId").asText());assertEquals("DRAFT",draft.get("status").asText());
 }
}
