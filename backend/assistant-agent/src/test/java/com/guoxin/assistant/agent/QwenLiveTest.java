package com.guoxin.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.application.*;
import com.guoxin.assistant.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.env.StandardEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: invokes the configured real provider and consumes API quota. Never prints secrets. */
@EnabledIfSystemProperty(named="agent.live-test",matches="true")
class QwenLiveTest {
 @Test void streamingProviderSelectsReadToolThenPreparesTask(){
  var interpreter=new QwenInterpreter(new StandardEnvironment(),new ObjectMapper());
  AtomicInteger toolCalls=new AtomicInteger(),modelCalls=new AtomicInteger(),textChunks=new AtomicInteger();
  ExecutionEvents events=new ExecutionEvents(){public void emit(String t,Map<String,Object>p){if(t.equals("model.started"))modelCalls.incrementAndGet();if(t.equals("message.delta"))textChunks.incrementAndGet();}public void checkpoint(Map<String,Object>s){}};
  var actor=new Actor("11111111-1111-1111-1111-111111111111","DEMO001","张晨","综合管理部");
  var request=new RunRequest(actor,"live","live-run","live-task","请先调用todo_search查询我的出差待办，再准备筛选任务，不要审核。",Map.of(),Map.of());
  Map<String,Object>state=new LinkedHashMap<>(Map.of("skillId","todo-review","kind","TODO"));
  var result=interpreter.interpret(request,state,events,(tool,args)->{toolCalls.incrementAndGet();return ToolResult.ok(Map.of("items",List.of(),"totalCount",0));});
  assertEquals("TODO",result.get("kind"));assertTrue(toolCalls.get()>0,"provider should select a real read tool");assertTrue(modelCalls.get()>1,"tool result must be returned to the model");
  String narrative=interpreter.summarize(request,state,events,"已查询本人待办，请在组件中核对筛选范围；尚未执行审核。");
  assertFalse(narrative.isBlank());assertTrue(textChunks.get()>0,"the final narrative should arrive as real streamed text chunks");
 }
 @Test void naturalPassportRequestProducesStructuredMissingFieldsWithoutInventingDates()throws Exception{
  var mapper=new ObjectMapper();var interpreter=new QwenInterpreter(new StandardEnvironment(),mapper);
  var actor=new Actor("11111111-1111-1111-1111-111111111111","DEMO001","张晨","综合管理部");
  var request=new RunRequest(actor,"live-passport","live-passport-run","live-passport-task","我想借护照去日本旅游，请帮我准备申请。",Map.of(),Map.of());
  Map<String,Object>state=new LinkedHashMap<>(Map.of("skillId","passport-borrow","kind","CREDENTIAL","values",Map.of(),"profile",Map.of("applicantId",actor.id(),"name",actor.name(),"department",actor.department()),"metadata",Map.of("version","1.0.0","requiredFields",List.of("departureDate","arrivalBackDate","returnDate","purpose"))));
  ExecutionEvents events=new ExecutionEvents(){public void emit(String t,Map<String,Object>p){}public void checkpoint(Map<String,Object>s){}};
  var result=interpreter.interpret(request,state,events,(tool,args)->switch(tool){
   case "profile_get_self"->ToolResult.ok(Map.of("applicantId",actor.id(),"name",actor.name(),"department",actor.department()));
   case "credential_list_mine"->ToolResult.ok(Map.of("items",List.of(Map.of("id","passport-1","credentialType","PASSPORT","custodyStatus","IN_CUSTODY"))));
   case "form_get_schema"->ToolResult.ok(Map.of("version","1.0.0","requiredFields",List.of("departureDate","arrivalBackDate","returnDate","purpose")));
   default->ToolResult.error("TOOL_NOT_ALLOWED","VALIDATION","请选择证照只读工具");
  });
  assertEquals("CREDENTIAL",result.get("kind"));assertEquals("CONTINUE",result.get("nextAction"));
  var values=mapper.readTree(String.valueOf(result.get("valuesJson")));
  assertEquals("BORROW",values.path("requestMode").asText());assertEquals("PASSPORT",values.path("credentialType").asText());assertEquals("日本",values.path("destination").asText());
  for(String date:List.of("departureDate","arrivalBackDate","returnDate"))assertTrue(!values.has(date)||values.get(date).isNull()||values.get(date).asText().isBlank(),"unspecified dates must remain missing");
 }
}
