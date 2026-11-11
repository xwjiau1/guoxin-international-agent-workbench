package com.guoxin.assistant.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.application.*;
import com.guoxin.assistant.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowAgentRuntimeTest {
 private static final Actor ACTOR=new Actor("11111111-1111-1111-1111-111111111111","DEMO001","张晨","综合管理部");
 private final List<String> calls=new ArrayList<>();
 private final ExecutionEvents events=new ExecutionEvents(){public void emit(String t,Map<String,Object>p){} public void checkpoint(Map<String,Object>s){}};
 private WorkflowAgentRuntime runtime(BusinessPort port){return new WorkflowAgentRuntime(port,new ObjectMapper(),new MockEnvironment().withProperty("agent.model-mode","test"),new DefaultListableBeanFactory().getBeanProvider(DataSource.class));}
 private ToolResult base(String tool,Map<String,Object>a){
  calls.add(tool);
  return switch(tool){
   case "profile_get_self"->ToolResult.ok(Map.of("applicantId",ACTOR.id(),"name",ACTOR.name(),"department",ACTOR.department()));
   case "form_get_schema"->ToolResult.ok(Map.of("version","1.0.0","fields",List.of()));
   case "credential_list_mine"->ToolResult.ok(Map.of("items",List.of(Map.of("id","cert-1"))));
   case "form_validate"->new ToolResult(false,"VALIDATION_REQUIRED","VALIDATION","请补充日期",Map.of("returnDate","归还日期必填"),Map.of());
   case "draft_save"->ToolResult.ok(Map.of("draftId","draft-1","version",1,"values",a.get("values")));
   default->ToolResult.ok(Map.of());
  };
 }
 private RunRequest req(Map<String,Object>s,String text,Map<String,Object>a){return new RunRequest(ACTOR,"conversation","run-"+UUID.randomUUID(),"task",text,a,s);}
 @Test void missingFieldsThenSaveDraftWithoutSubmitting(){
  var agent=runtime((t,a,u,o)->base(t,a));
  var first=agent.run(req(Map.of(),"去日本旅游借护照",Map.of()),events);
  assertEquals("WAITING_INPUT",first.status());assertEquals("FIELD_COLLECTION",first.components().getFirst().get("type"));
  var saved=agent.run(req(first.state(),"保存草稿",Map.of()),events);
  assertEquals("DRAFT_SAVED",saved.status());assertEquals("draft-1",saved.state().get("draftId"));assertFalse(calls.contains("submit_application"));
 }
 @Test void confirmationWithoutSnapshotNeverWrites(){
  var agent=runtime((t,a,u,o)->{calls.add(t);return ToolResult.ok(Map.of());});
  var result=agent.run(req(Map.of(),"",Map.of("type","CONFIRM","payload",Map.of("confirmationId","forged"))),events);
  assertEquals("WAITING_INPUT",result.status());assertFalse(calls.contains("submit_application"));assertFalse(calls.contains("review_todo"));
 }
 @Test void repeatedConfirmationReturnsReceiptWithoutSecondMutation(){
  AtomicInteger writes=new AtomicInteger();
  var agent=runtime((t,a,u,o)->{if(t.equals("submit_application")){writes.incrementAndGet();return ToolResult.ok(Map.of("applicationId","app-1","businessNo","NO1","status","IN_APPROVAL"));}return base(t,a);});
  Map<String,Object> pending=new LinkedHashMap<>(Map.of("id","confirm-1","kind","SUBMIT","payload",Map.of("values",Map.of()),"expiresAt",java.time.Instant.now().plusSeconds(60).toString()));
  Map<String,Object> state=new LinkedHashMap<>(Map.of("kind","CREDENTIAL","pendingConfirmation",pending));
  var action=Map.<String,Object>of("type","CONFIRM","payload",Map.of("confirmationId","confirm-1"));
  var first=agent.run(req(state,"",action),events);assertEquals("SUCCEEDED",first.status());
  var second=agent.run(req(first.state(),"",action),events);assertEquals("SUCCEEDED",second.status());assertEquals(1,writes.get());
 }
 @Test void expiredConfirmationDoesNotExecute(){
  var agent=runtime((t,a,u,o)->{calls.add(t);return ToolResult.ok(Map.of());});
  Map<String,Object> pending=new LinkedHashMap<>(Map.of("id","old","kind","SUBMIT","payload",Map.of(),"expiresAt","2020-01-01T00:00:00Z"));
  var result=agent.run(req(new LinkedHashMap<>(Map.of("pendingConfirmation",pending)),"",Map.of("type","CONFIRM","payload",Map.of("confirmationId","old"))),events);
  assertEquals("WAITING_INPUT",result.status());assertFalse(calls.contains("submit_application"));
 }
 @Test void committedWriteWithLostResponseIsReconciledWithoutResubmit(){
  AtomicInteger writes=new AtomicInteger();
  var agent=runtime((t,a,u,o)->{
   if(t.equals("submit_application")){writes.incrementAndGet();return ToolResult.error("TIMEOUT","UNKNOWN_WRITE","结果未知");}
   if(t.equals("operation_get"))return ToolResult.ok(Map.of("status","SUCCEEDED","data",Map.of("applicationId","app-1","businessNo","ONE","status","IN_APPROVAL")));
   return base(t,a);
  });
  Map<String,Object> p=new LinkedHashMap<>(Map.of("id","c","kind","SUBMIT","payload",Map.of("values",Map.of()),"expiresAt",java.time.Instant.now().plusSeconds(60).toString()));
  var result=agent.run(req(new LinkedHashMap<>(Map.of("pendingConfirmation",p)),"",Map.of("type","CONFIRM","payload",Map.of("confirmationId","c"))),events);
  assertEquals("SUCCEEDED",result.status());assertEquals(1,writes.get());assertEquals("app-1",result.state().get("applicationId"));
 }
 @Test void newRequestAfterSuccessDoesNotReuseOldOperationOrFields(){
  var agent=runtime((t,a,u,o)->base(t,a));
  Map<String,Object> state=new LinkedHashMap<>();state.put("kind","CREDENTIAL");state.put("status","SUCCEEDED");state.put("operationId","old-operation");state.put("values",new LinkedHashMap<>(Map.of("departureDate","2027-12-01")));state.put("pendingConfirmation",Map.of("id","old"));
  var result=agent.run(req(state,"借护照去日本旅游",Map.of()),events);
  assertEquals("WAITING_INPUT",result.status());assertFalse(result.state().containsKey("operationId"));assertFalse(((Map<?,?>)result.state().get("values")).containsKey("departureDate"));
 }
 @Test void permissionFailureDoesNotRetryWrite(){
  AtomicInteger writes=new AtomicInteger();
  var agent=runtime((t,a,u,o)->{if(t.equals("submit_application")){writes.incrementAndGet();throw new SecurityException();}return base(t,a);});
  Map<String,Object> p=new LinkedHashMap<>(Map.of("id","c","kind","SUBMIT","payload",Map.of("values",Map.of()),"expiresAt",java.time.Instant.now().plusSeconds(60).toString()));
  var result=agent.run(req(new LinkedHashMap<>(Map.of("pendingConfirmation",p)),"",Map.of("type","CONFIRM","payload",Map.of("confirmationId","c"))),events);
  assertEquals(1,writes.get());assertEquals("WAITING_INPUT",result.status());
 }
 @Test void replayingCompletedRunDoesNotCallModelOrBusiness(){
  AtomicInteger invoked=new AtomicInteger();
  var agent=runtime((t,a,u,o)->{invoked.incrementAndGet();throw new AssertionError("completed run must not execute tools");});
  Map<String,Object>state=new LinkedHashMap<>(Map.of("lastCompletedRunId","finished-run","status","DRAFT_SAVED","reply","草稿已保存","components",List.of()));
  var result=agent.run(new RunRequest(ACTOR,"conversation","finished-run","task","保存草稿",Map.of(),state),events);
  assertEquals("DRAFT_SAVED",result.status());assertEquals("草稿已保存",result.reply());assertEquals(0,invoked.get());
 }
 @Test void queuedCancellationPreventsNewWrite(){
  AtomicInteger writes=new AtomicInteger();
  var agent=runtime((t,a,u,o)->{if(t.equals("submit_application"))writes.incrementAndGet();return base(t,a);});
  Map<String,Object> p=new LinkedHashMap<>(Map.of("id","c","kind","SUBMIT","payload",Map.of("values",Map.of()),"expiresAt",java.time.Instant.now().plusSeconds(60).toString()));
  ExecutionEvents cancelled=new ExecutionEvents(){public void emit(String t,Map<String,Object>p){}public void checkpoint(Map<String,Object>s){}public boolean isCancellationRequested(){return true;}};
  var result=agent.run(req(new LinkedHashMap<>(Map.of("pendingConfirmation",p)),"",Map.of("type","CONFIRM","payload",Map.of("confirmationId","c"))),cancelled);
  assertEquals("CANCELLED",result.status());assertEquals(0,writes.get());
 }
}
