package com.guoxin.assistant.business;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guoxin.assistant.application.BusinessPort;
import com.guoxin.assistant.domain.Actor;
import com.guoxin.assistant.domain.ToolResult;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent simulated enterprise system. Identity always comes from the server Actor. */
@Service
public class MockBusinessService implements BusinessPort {
 public static final String ZHANG="11111111-1111-1111-1111-111111111111", LI="22222222-2222-2222-2222-222222222222", WANG="33333333-3333-3333-3333-333333333333";
 public static final Set<String> TYPES=Set.of("TRAVEL_APPLICATION","REIMBURSEMENT","PRIVATE_OVERSEAS","PRIVATE_CREDENTIAL");
 private static final Set<String> FORM_FIELDS=Set.of("applicantId","name","department","employeeNo","requestMode","credentialType","credentialId","destination","departureDate","arrivalBackDate","returnDate","purpose","applicationReason","remark");
 private final JdbcTemplate jdbc; private final ObjectMapper json; private final TransactionTemplate tx; private final Clock clock;
 private final String fault; private final AtomicBoolean faultUsed=new AtomicBoolean();
 @Autowired public MockBusinessService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Environment env){
  this(jdbc,manager,json,Clock.system(ZoneId.of("Asia/Shanghai")),
   Arrays.stream(env.getActiveProfiles()).anyMatch(p->p.equals("local-demo")||p.equals("test"))?env.getProperty("mock.fault-plan",""):"");
 }
 public MockBusinessService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,Clock clock,String fault){
  this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; this.clock=clock; this.fault=fault;
  ResourceDatabasePopulator ddl=new ResourceDatabasePopulator(new ClassPathResource("schema-business.sql")); ddl.execute(Objects.requireNonNull(jdbc.getDataSource()));
  seed();
 }
 @Override public ToolResult invoke(String tool,Map<String,Object> args,Actor actor,String operationId){
  if(actor==null||actor.id()==null) return error("AUTH_REQUIRED","AUTH","请先登录");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM gx_biz_employee WHERE id=?",Integer.class,actor.id())==0) return error("FORBIDDEN","AUTH","员工账号不可用");
  Map<String,Object> a=args==null?Map.of():args;
  if(fault.equals("READ_FAIL_ONCE")&&Set.of("todo_search","credential_list_mine").contains(tool)&&faultUsed.compareAndSet(false,true)) return error("NETWORK_TEMPORARY","TRANSIENT","模拟连接中断，请稍后重试");
  if(fault.equals("RATE_LIMIT_ONCE")&&tool.equals("todo_search")&&faultUsed.compareAndSet(false,true))return new ToolResult(false,"RATE_LIMITED","RATE_LIMIT","服务繁忙",Map.of(),Map.of("retryAfterMs",500));
  try {
   if(Set.of("draft_save","submit_application","review_todo").contains(tool)){
    if(operationId==null||operationId.isBlank())return error("OPERATION_REQUIRED","VALIDATION","写入必须提供服务端操作编号");
    ToolResult result=write(tool,a,actor,operationId);
    if(result.success()&&tool.equals("submit_application")&&fault.equals("COMMIT_THEN_TIMEOUT")&&faultUsed.compareAndSet(false,true))return new ToolResult(false,"UNKNOWN_WRITE","UNKNOWN_WRITE","提交响应丢失，请按操作编号核实",Map.of(),Map.of("operationId",operationId));
    return result;
   }
   return switch(tool){
    case "profile_get_self" -> profile(actor);
    case "form_get_schema" -> schema(text(a,"formType","PRIVATE_CREDENTIAL"));
    case "credential_list_mine" -> credentials(a,actor);
    case "form_validate" -> validate(a,actor);
    case "draft_get" -> draft(a,actor);
    case "application_get" -> application(a,actor);
    case "todo_search","todo_statistics" -> todos(a,actor,tool.equals("todo_statistics"));
    case "operation_get" -> receipt(text(a,"operationId",operationId==null?"":operationId),actor);
    default -> error("TOOL_NOT_FOUND","BUSINESS_RULE","未发布的业务工具："+tool);
   };
  }catch(IllegalArgumentException ex){return error("INVALID_ARGUMENT","VALIDATION",ex.getMessage());}
 }
 private ToolResult write(String tool,Map<String,Object>a,Actor actor,String op){
  String hash=hash(a);
  return tx.execute(status->{
   jdbc.update("INSERT INTO gx_biz_receipt(operation_id,actor_id,tool_name,request_hash) VALUES(?,?,?,?) ON CONFLICT(operation_id) DO NOTHING",op,actor.id(),tool,hash);
   Map<String,Object> r=jdbc.queryForMap("SELECT * FROM gx_biz_receipt WHERE operation_id=? FOR UPDATE",op);
   if(!actor.id().equals(r.get("actor_id")))return error("NOT_FOUND","AUTH","操作不存在");
   if(!tool.equals(r.get("tool_name"))||!hash.equals(r.get("request_hash")))return error("IDEMPOTENCY_CONFLICT","CONFLICT","同一操作编号不能提交不同内容");
   if(r.get("response_json")!=null)return parseResult(r.get("response_json").toString());
   ToolResult result=switch(tool){case "draft_save"->saveDraft(a,actor);case "submit_application"->submit(a,actor,op);case "review_todo"->review(a,actor,op);default->throw new IllegalArgumentException("invalid write");};
   jdbc.update("UPDATE gx_biz_receipt SET response_json=? WHERE operation_id=?",encode(result),op);return result;
  });
 }
 private ToolResult profile(Actor actor){
  Map<String,Object> r=jdbc.queryForMap("SELECT * FROM gx_biz_employee WHERE id=?",actor.id());
  return ToolResult.ok(map("applicantId",actor.id(),"id",actor.id(),"employeeNo",r.get("employee_no"),"name",r.get("name"),"department",r.get("department")));
 }
 private ToolResult credentials(Map<String,Object>a,Actor actor){
  String sql="SELECT * FROM gx_biz_credential WHERE owner_id=?";List<Object> p=new ArrayList<>(List.of(actor.id()));
  if(!text(a,"credentialType","").isBlank()){sql+=" AND credential_type=?";p.add(a.get("credentialType"));}
  List<Map<String,Object>> items=jdbc.queryForList(sql+" ORDER BY id",p.toArray()).stream().map(r->map("id",r.get("id"),"credentialId",r.get("id"),"credentialType",r.get("credential_type"),"maskedNo",r.get("masked_no"),"custodyStatus",r.get("custody_status"),"status",r.get("custody_status"),"validUntil",r.get("valid_until").toString(),"version",r.get("version"))).toList();
  return ToolResult.ok(map("items",items,"totalCount",items.size()));
 }
 private ToolResult schema(String type){
  if(!TYPES.contains(type))return error("FORM_NOT_FOUND","VALIDATION","未知单据类型");
  List<Map<String,Object>> fs=new ArrayList<>();
  for(String k:List.of("applicantId","name","department"))fs.add(field(k,switch(k){case "name"->"姓名";case "department"->"部门";default->"申请人";},"string",false,false));
  fs.add(with(field("requestMode","申请方式","enum",true,true),"options",List.of("BORROW","APPLY")));
  fs.add(with(field("credentialType","证照类型","enum",true,true),"options",List.of("PASSPORT","HK_MACAO_PERMIT")));
  fs.add(with(field("credentialId","本人证照","string",false,true),"requiredWhen",Map.of("requestMode","BORROW")));
  fs.add(field("destination","目的地","string",true,true));fs.add(field("departureDate","出发日期","date",true,true));fs.add(field("arrivalBackDate","返程日期","date",true,true));
  fs.add(with(field("returnDate","归还日期","date",false,true),"requiredWhen",Map.of("requestMode","BORROW")));
  fs.add(field("purpose","用途","text",true,true));fs.add(with(field("applicationReason","办理原因","text",false,true),"requiredWhen",Map.of("requestMode","APPLY")));fs.add(field("remark","备注","text",false,true));
  return ToolResult.ok(map("formType",type,"version","1.0.0","metadataVersion","1.0.0","fields",fs,"workflowVersion","single-approval-1","requiredFields",List.of("requestMode","credentialType","destination","departureDate","arrivalBackDate","purpose")));
 }
 private ToolResult validate(Map<String,Object>a,Actor actor){
  String type=text(a,"formType","PRIVATE_CREDENTIAL");if(!TYPES.contains(type))return error("FORM_NOT_FOUND","VALIDATION","未知单据类型");
  if(!text(a,"metadataVersion","1.0.0").equals("1.0.0"))return error("SCHEMA_CHANGED","CONFLICT","单据字段规则已变化，请刷新元数据");
  Map<String,Object> v=values(a);Map<String,String> errors=new LinkedHashMap<>();boolean full=!text(a,"mode","SUBMIT").equals("DRAFT");
  for(String key:v.keySet())if(!FORM_FIELDS.contains(key))errors.put(key,"当前单据未定义该字段，请刷新元数据");
  for(String key:FORM_FIELDS)if(v.containsKey(key)&&v.get(key)!=null&&!(v.get(key) instanceof String)&&!Set.of("applicantId","name","department","employeeNo").contains(key))errors.put(key,"请填写文本或合法选项值");
  if(full){for(String k:List.of("requestMode","credentialType","destination","departureDate","arrivalBackDate","purpose"))if(blank(v.get(k)))errors.put(k,"此项必填");
   if("BORROW".equals(v.get("requestMode")))for(String k:List.of("credentialId","returnDate"))if(blank(v.get(k)))errors.put(k,"借用时必填");
   if("APPLY".equals(v.get("requestMode"))&&blank(v.get("applicationReason")))errors.put("applicationReason","办理时请填写原因");}
  checkEnum(v,errors,"requestMode",Set.of("BORROW","APPLY"));checkEnum(v,errors,"credentialType",Set.of("PASSPORT","HK_MACAO_PERMIT"));
  Map<String,LocalDate> dates=new HashMap<>();for(String k:List.of("departureDate","arrivalBackDate","returnDate"))if(!blank(v.get(k)))try{dates.put(k,LocalDate.parse(v.get(k).toString()));}catch(Exception ex){errors.put(k,"请使用 YYYY-MM-DD 日期格式");}
  if(dates.containsKey("departureDate")&&dates.get("departureDate").isBefore(LocalDate.now(clock)))errors.put("departureDate","出发日期不能早于今天");
  if(dates.containsKey("departureDate")&&dates.containsKey("arrivalBackDate")&&dates.get("arrivalBackDate").isBefore(dates.get("departureDate")))errors.put("arrivalBackDate","返程不能早于出发");
  if(dates.containsKey("returnDate")&&dates.containsKey("arrivalBackDate")&&dates.get("returnDate").isBefore(dates.get("arrivalBackDate")))errors.put("returnDate","归还不能早于返程");
  for(String k:List.of("purpose","applicationReason","remark"))if(!blank(v.get(k))){int n=v.get(k).toString().length();if(n>(k.equals("remark")?1000:500)||(!k.equals("remark")&&n<2))errors.put(k,"内容长度不符合要求");}
  if(!blank(v.get("credentialId"))){List<Map<String,Object>> cr=jdbc.queryForList("SELECT * FROM gx_biz_credential WHERE id=? AND owner_id=?",v.get("credentialId"),actor.id());
   if(cr.isEmpty())errors.put("credentialId","请选择本人持有的证照");else{Map<String,Object> c=cr.getFirst();if(!blank(v.get("credentialType"))&&!v.get("credentialType").equals(c.get("credential_type")))errors.put("credentialType","证照类型与所选证照不符");if(full&&!"IN_CUSTODY".equals(c.get("custody_status")))errors.put("credentialId","该证照当前不在库，无法借用");LocalDate valid=LocalDate.parse(c.get("valid_until").toString());if(full&&valid.isBefore(dates.getOrDefault("arrivalBackDate",LocalDate.now(clock))))errors.put("credentialId","该证照有效期不足");}}
  if(!errors.isEmpty())return new ToolResult(false,"VALIDATION_REQUIRED","VALIDATION","请补充或修正字段",errors,map("values",trusted(v,actor),"missingFields",new ArrayList<>(errors.keySet())));
  return ToolResult.ok(map("valid",true,"values",trusted(v,actor),"metadataVersion","1.0.0"));
 }
 private ToolResult saveDraft(Map<String,Object>a,Actor actor){
  ToolResult check=validate(with(a,"mode","DRAFT"),actor);if(!check.success())return check;
  String id=text(a,"draftId","");Map<String,Object> v=values(a);String type=text(a,"formType","PRIVATE_CREDENTIAL");
  if(id.isBlank()){id=uuid();jdbc.update("INSERT INTO gx_biz_draft(id,owner_id,form_type,metadata_version,values_json) VALUES(?,?,?,?,?)",id,actor.id(),type,"1.0.0",encode(trusted(v,actor)));}
  else{List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM gx_biz_draft WHERE id=? AND owner_id=? FOR UPDATE",id,actor.id());if(old.isEmpty())return error("NOT_FOUND","AUTH","草稿不存在");Map<String,Object> row=old.getFirst();if(!"DRAFT".equals(row.get("status")))return error("STATE_CONFLICT","CONFLICT","草稿已提交");if(!type.equals(row.get("form_type")))return error("STATE_CONFLICT","CONFLICT","不能修改已有草稿的单据类型");long ver=number(a,"expectedVersion",-1);if(ver!=((Number)row.get("version")).longValue())return error("VERSION_CONFLICT","CONFLICT","草稿已被修改或缺少版本，请刷新后保存");jdbc.update("UPDATE gx_biz_draft SET values_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",encode(trusted(v,actor)),id);}
  return draft(Map.of("draftId",id),actor);
 }
 private ToolResult draft(Map<String,Object>a,Actor actor){
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM gx_biz_draft WHERE id=? AND owner_id=?",text(a,"draftId",""),actor.id());if(rows.isEmpty())return error("NOT_FOUND","AUTH","草稿不存在");return ToolResult.ok(draftMap(rows.getFirst()));
 }
 private Map<String,Object> draftMap(Map<String,Object>r){return map("id",r.get("id"),"draftId",r.get("id"),"formType",r.get("form_type"),"metadataVersion",r.get("metadata_version"),"values",decode(r.get("values_json")),"version",r.get("version"),"status",r.get("status"),"updatedAt",r.get("updated_at").toString());}
 public List<Map<String,Object>> listDrafts(Actor actor){return jdbc.queryForList("SELECT * FROM gx_biz_draft WHERE owner_id=? AND status='DRAFT' ORDER BY updated_at DESC,id",actor.id()).stream().map(this::draftMap).toList();}
 private ToolResult submit(Map<String,Object>a,Actor actor,String op){
  String draftId=text(a,"draftId","");if(!draftId.isBlank()){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM gx_biz_draft WHERE id=? AND owner_id=? FOR UPDATE",draftId,actor.id());if(rows.isEmpty())return error("NOT_FOUND","AUTH","草稿不存在");Map<String,Object> row=rows.getFirst();if(!"DRAFT".equals(row.get("status")))return error("STATE_CONFLICT","CONFLICT","草稿已经提交");if(!text(a,"formType","PRIVATE_CREDENTIAL").equals(row.get("form_type")))return error("STATE_CONFLICT","CONFLICT","提交类型与草稿不一致");long expected=number(a,"expectedDraftVersion",number(a,"expectedVersion",-1));if(expected!=((Number)row.get("version")).longValue())return error("VERSION_CONFLICT","CONFLICT","草稿已变化，请重新读取并确认");}
  Map<String,Object> input=values(a);String credentialId=text(input,"credentialId","");boolean borrow="BORROW".equals(input.get("requestMode"));
  if(borrow&&!credentialId.isBlank())jdbc.queryForList("SELECT id FROM gx_biz_credential WHERE id=? AND owner_id=? FOR UPDATE",credentialId,actor.id());
  ToolResult check=validate(with(a,"mode","SUBMIT"),actor);if(!check.success())return check;
  if(borrow&&jdbc.queryForObject("SELECT COUNT(*) FROM gx_biz_credential_reservation WHERE credential_id=?",Integer.class,credentialId)>0)return error("CREDENTIAL_RESERVED","BUSINESS_RULE","该证照存在在途借用申请，请先查询原申请状态");
  String id=uuid(),wf=uuid(),todo=uuid(),bn="GX-"+id.substring(0,8).toUpperCase(),pn="PROC-"+wf.substring(0,8).toUpperCase();Map<String,Object> v=trusted(values(a),actor);
  jdbc.update("INSERT INTO gx_biz_document(id,business_no,owner_id,form_type,metadata_version,status,values_json) VALUES(?,?,?,?,?,'IN_APPROVAL',?)",id,bn,actor.id(),text(a,"formType","PRIVATE_CREDENTIAL"),"1.0.0",encode(v));
  if(borrow)jdbc.update("INSERT INTO gx_biz_credential_reservation(credential_id,document_id) VALUES(?,?)",credentialId,id);
  jdbc.update("INSERT INTO gx_biz_workflow(id,process_no,document_id,status) VALUES(?,?,?,'IN_APPROVAL')",wf,pn,id);
  jdbc.update("INSERT INTO gx_biz_todo(id,workflow_id,document_id,assignee_id,arrived_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",todo,wf,id,actor.id().equals(ZHANG)?LI:ZHANG);
  if(!draftId.isBlank())jdbc.update("UPDATE gx_biz_draft SET status='SUBMITTED',document_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id,draftId);
  return ToolResult.ok(map("applicationId",id,"businessNo",bn,"processNo",pn,"processId",wf,"status","IN_APPROVAL","businessStatus","IN_APPROVAL","operationId",op,"values",v));
 }
 private ToolResult application(Map<String,Object>a,Actor actor){
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT d.*,w.process_no FROM gx_biz_document d JOIN gx_biz_workflow w ON w.document_id=d.id WHERE d.id=? AND d.owner_id=?",text(a,"applicationId",text(a,"documentId","")),actor.id());
  if(rows.isEmpty())return error("NOT_FOUND","AUTH","单据不存在");Map<String,Object> r=rows.getFirst();return ToolResult.ok(map("applicationId",r.get("id"),"businessNo",r.get("business_no"),"processNo",r.get("process_no"),"status",r.get("status"),"values",decode(r.get("values_json"))));
 }
 private ToolResult review(Map<String,Object>a,Actor actor,String op){
  String decision=text(a,"decision","");String comment=text(a,"comment","").strip();if(!Set.of("APPROVE","REJECT").contains(decision))return error("INVALID_DECISION","VALIDATION","请选择同意或驳回");if(comment.length()>500||(decision.equals("REJECT")&&comment.length()<2))return new ToolResult(false,"VALIDATION_REQUIRED","VALIDATION","驳回请填写2至500字意见，审核意见最多500字",Map.of("comment","请填写合法审核意见"),Map.of());
  String id=text(a,"todoId","");List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM gx_biz_todo WHERE id=? AND assignee_id=? FOR UPDATE",id,actor.id());if(rows.isEmpty())return error("NOT_FOUND","AUTH","待办不存在");Map<String,Object> t=rows.getFirst();
  if(!"PENDING".equals(t.get("status")))return error("STATE_CONFLICT","CONFLICT","该待办已经处理");long expected=number(a,"expectedVersion",number(a,"version",-1));if(expected<0||expected!=((Number)t.get("version")).longValue())return error("VERSION_CONFLICT","CONFLICT","待办已变化，请重新确认");
  String next=decision.equals("APPROVE")?"APPROVED":"REJECTED";int changed=jdbc.update("UPDATE gx_biz_todo SET status=?,version=version+1 WHERE id=? AND assignee_id=? AND version=? AND status='PENDING'",next,id,actor.id(),expected);if(changed!=1)return error("STATE_CONFLICT","CONFLICT","待办状态变化");
  jdbc.update("UPDATE gx_biz_document SET status=?,version=version+1 WHERE id=?",next,t.get("document_id"));jdbc.update("UPDATE gx_biz_workflow SET status=? WHERE id=?",next,t.get("workflow_id"));jdbc.update("INSERT INTO gx_biz_approval_record(id,todo_id,actor_id,decision,comment_text,operation_id) VALUES(?,?,?,?,?,?)",uuid(),id,actor.id(),decision,comment,op);
  if(decision.equals("REJECT"))jdbc.update("DELETE FROM gx_biz_credential_reservation WHERE document_id=?",t.get("document_id"));
  return ToolResult.ok(map("todoId",id,"decision",decision,"status",next,"operationId",op,"version",expected+1));
 }
 private ToolResult receipt(String op,Actor actor){
  List<Map<String,Object>> rows=jdbc.queryForList("SELECT response_json FROM gx_biz_receipt WHERE operation_id=? AND actor_id=?",op,actor.id());if(rows.isEmpty())return ToolResult.ok(map("operationId",op,"status","NOT_FOUND"));if(rows.getFirst().get("response_json")==null)return ToolResult.ok(map("operationId",op,"status","IN_PROGRESS"));ToolResult original=parseResult(rows.getFirst().get("response_json").toString());return ToolResult.ok(map("operationId",op,"status",original.success()?"SUCCEEDED":"FAILED","result",original,"data",original.data()));
 }
 private ToolResult todos(Map<String,Object>a,Actor actor,boolean statistics){
  StringBuilder q=new StringBuilder(" FROM gx_biz_todo t JOIN gx_biz_document d ON d.id=t.document_id JOIN gx_biz_employee e ON e.id=d.owner_id WHERE t.assignee_id=?");List<Object> p=new ArrayList<>(List.of(actor.id()));
  String status=text(a,"status","PENDING");if(!status.equals("ALL")){if(!Set.of("PENDING","APPROVED","REJECTED").contains(status))throw new IllegalArgumentException("待办状态无效");q.append(" AND t.status=?");p.add(status);}
  Object types=a.get("types");if(types==null&&!blank(a.get("formType")))types=List.of(a.get("formType"));if(types instanceof String s&&!s.isBlank())types=List.of(s);
  if(types instanceof Collection<?> list&&!list.isEmpty()){if(!TYPES.containsAll(list))throw new IllegalArgumentException("单据类型无效");q.append(" AND d.form_type IN (").append(String.join(",",Collections.nCopies(list.size(),"?"))).append(")");p.addAll(list);}
  String dateField=text(a,"dateField","arrivedAt");String col=switch(dateField){case "arrivedAt","arrived_at"->"t.arrived_at";case "createdAt","applicationDate","created_at"->"d.created_at";default->throw new IllegalArgumentException("日期字段无效");};
  if(!blank(a.get("from"))){q.append(" AND ").append(col).append(">=?");p.add(timestamp(a.get("from").toString()));}if(!blank(a.get("to"))){q.append(" AND ").append(col).append("<?");p.add(timestamp(a.get("to").toString()));}
  String keyword=text(a,"keyword","");if(!keyword.isBlank()){q.append(" AND (d.values_json LIKE ? ESCAPE '!' OR e.name LIKE ? ESCAPE '!' OR d.business_no LIKE ? ESCAPE '!')");String term="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";p.add(term);p.add(term);p.add(term);}
  long total=jdbc.queryForObject("SELECT COUNT(*)"+q,Long.class,p.toArray());
  if(statistics){List<Map<String,Object>> counts=jdbc.queryForList("SELECT d.form_type AS type,COUNT(*) AS count"+q+" GROUP BY d.form_type ORDER BY d.form_type",p.toArray());return ToolResult.ok(map("totalCount",total,"byType",counts,"filters",a));}
  int limit=(int)Math.min(100,Math.max(1,number(a,"limit",100)));long offset=number(a,"cursor",0);if(offset<0)throw new IllegalArgumentException("分页游标无效");p.add(limit);p.add(offset);
  List<Map<String,Object>> items=jdbc.queryForList("SELECT t.*,d.business_no,d.form_type,d.values_json,d.created_at,e.name AS applicant_name"+q+" ORDER BY t.arrived_at DESC,t.id LIMIT ? OFFSET ?",p.toArray()).stream().map(r->map("id",r.get("id"),"todoId",r.get("id"),"documentId",r.get("document_id"),"businessNo",r.get("business_no"),"formType",r.get("form_type"),"type",r.get("form_type"),"applicantName",r.get("applicant_name"),"arrivedAt",r.get("arrived_at").toString(),"createdAt",r.get("created_at").toString(),"status",r.get("status"),"version",r.get("version"),"values",decode(r.get("values_json")),"summary",Objects.toString(decode(r.get("values_json")).get("summary"),""))).toList();
  return ToolResult.ok(map("items",items,"totalCount",total,"nextCursor",offset+items.size()<total?Long.toString(offset+items.size()):null,"filters",a));
 }
 private void seed(){tx.executeWithoutResult(s->{
  for(Object[] row:List.of(new Object[]{ZHANG,"DEMO001","张晨","综合管理部"},new Object[]{LI,"DEMO002","李敏","综合管理部"},new Object[]{WANG,"DEMO003","王宁","财务部"}))jdbc.update("INSERT INTO gx_biz_employee(id,employee_no,name,department) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING",row);
  seedCredential("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1",ZHANG,"PASSPORT","E****0123","IN_CUSTODY");seedCredential("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2",ZHANG,"HK_MACAO_PERMIT","C****0456","BORROWED");seedCredential("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3",LI,"PASSPORT","E****0789","IN_CUSTODY");
  String[] types={"TRAVEL_APPLICATION","TRAVEL_APPLICATION","TRAVEL_APPLICATION","TRAVEL_APPLICATION","REIMBURSEMENT","REIMBURSEMENT","PRIVATE_OVERSEAS","PRIVATE_CREDENTIAL","TRAVEL_APPLICATION","REIMBURSEMENT","PRIVATE_OVERSEAS","PRIVATE_CREDENTIAL"};
  for(int i=0;i<12;i++){String id=String.format("bbbbbbbb-bbbb-bbbb-bbbb-%012d",i+1),wf=String.format("cccccccc-cccc-cccc-cccc-%012d",i+1),todo=String.format("dddddddd-dddd-dddd-dddd-%012d",i+1);String date=i==3?"2026-11-05T09:00:00+08:00":"2026-10-"+String.format("%02d",5+i)+"T09:00:00+08:00";String applicant=i<8?(i%2==0?WANG:LI):ZHANG;Map<String,Object> values=map("destination",i%2==0?"上海":"北京","purpose",i<4?"项目交流":"业务办理","amount",i==4?"1680.00":"800.00","summary",(i%2==0?"上海":"北京")+" · "+(i<4?"项目交流":"业务办理"));
   jdbc.update("INSERT INTO gx_biz_document(id,business_no,owner_id,form_type,metadata_version,status,values_json,created_at) VALUES(?,?,?,?,?,'IN_APPROVAL',?,?) ON CONFLICT(id) DO NOTHING",id,"GX-DEMO-"+String.format("%03d",i+1),applicant,types[i],"1.0.0",encode(values),timestamp(date).toInstant().minusSeconds(86400).atOffset(ZoneOffset.UTC));
   jdbc.update("INSERT INTO gx_biz_workflow(id,process_no,document_id,status) VALUES(?,?,?,'IN_APPROVAL') ON CONFLICT(id) DO NOTHING",wf,"PROC-DEMO-"+(i+1),id);
   jdbc.update("INSERT INTO gx_biz_todo(id,workflow_id,document_id,assignee_id,arrived_at) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",todo,wf,id,i<8?ZHANG:LI,timestamp(date));
  }
 });}
 private void seedCredential(String id,String owner,String type,String no,String status){jdbc.update("INSERT INTO gx_biz_credential(id,owner_id,credential_type,masked_no,custody_status,valid_until) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",id,owner,type,no,status,java.sql.Date.valueOf("2035-12-31"));}
 private Map<String,Object> trusted(Map<String,Object>v,Actor actor){Map<String,Object> out=new LinkedHashMap<>(v);out.putAll(profile(actor).data());out.remove("id");return out;}
 @SuppressWarnings("unchecked") private Map<String,Object> values(Map<String,Object>a){Object v=a.get("values");return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of();}
 private static Map<String,Object> field(String key,String label,String type,boolean required,boolean editable){return map("key",key,"label",label,"type",type,"required",required,"editable",editable);}
 private static void checkEnum(Map<String,Object>v,Map<String,String> errors,String key,Set<String> allowed){if(!blank(v.get(key))&&!allowed.contains(v.get(key)))errors.put(key,"请选择合法选项："+allowed);}
 private static boolean blank(Object x){return x==null||x.toString().isBlank();}
 private static String text(Map<String,Object> m,String k,String fallback){return m.get(k)==null?fallback:m.get(k).toString();}
 private static long number(Map<String,Object>m,String k,long fallback){try{return m.get(k)==null?fallback:Long.parseLong(m.get(k).toString());}catch(Exception ex){throw new IllegalArgumentException(k+"必须是整数");}}
 private static Timestamp timestamp(String v){try{if(v.length()==10)return Timestamp.from(LocalDate.parse(v).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());return Timestamp.from(OffsetDateTime.parse(v).toInstant());}catch(Exception e){throw new IllegalArgumentException("日期格式无效，请使用ISO日期或时间");}}
 private ToolResult error(String c,String cat,String message){return ToolResult.error(c,cat,message);}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("业务数据序列化失败",e);}}
 private Map<String,Object> decode(Object raw){try{return json.readValue(raw.toString(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("业务数据读取失败",e);}}
 private ToolResult parseResult(String raw){try{return json.readValue(raw,ToolResult.class);}catch(Exception e){throw new IllegalStateException("业务回执读取失败",e);}}
 private String hash(Map<String,Object> value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(sorted(value)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Object sorted(Object value){if(value instanceof Map<?,?> m){Map<String,Object> tree=new TreeMap<>();m.forEach((k,v)->tree.put(k.toString(),sorted(v)));return tree;}if(value instanceof List<?> list)return list.stream().map(this::sorted).toList();return value;}
 private static String uuid(){return UUID.randomUUID().toString();}
 private static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
 private static Map<String,Object> with(Map<String,Object> source,String key,Object value){Map<String,Object> m=new LinkedHashMap<>(source);m.put(key,value);return m;}
}
