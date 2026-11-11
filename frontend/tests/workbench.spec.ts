import {test,expect} from '@playwright/test';
import type {Page} from '@playwright/test';
const cid='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',tid='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const actor={id:'11111111-1111-1111-1111-111111111111',name:'张晨',employeeNo:'DEMO001',department:'综合管理部',csrfToken:'test-csrf'};
async function mockApi(page:Page,component?:Record<string,unknown>){
 const actions:Record<string,any>[]=[];
 const task={id:tid,conversationId:cid,type:'PRIVATE_CREDENTIAL',status:'WAITING_CONFIRMATION',version:3,components:component?[{componentId:'card-1',taskId:tid,version:2,status:'ACTIVE',...component}]:[]};
 await page.route('**/api/v1/**',async route=>{
  const path=new URL(route.request().url()).pathname.replace('/api/v1','');let data:unknown={};
  if(path==='/me')data=actor;
  else if(path==='/conversations')data={items:[{id:cid,title:'证照借用申请'}]};
  else if(path==='/drafts')data={items:[]};
  else if(path.endsWith('/events'))return route.fulfill({contentType:'text/event-stream',body:': heartbeat\n\n'});
  else if(path.endsWith('/actions')){actions.push(route.request().postDataJSON());data={runId:'run-1'};}
  else if(path.endsWith('/messages'))data={items:[{id:'m1',role:'ASSISTANT',content:'我已准备好办理信息，请核对。'}]};
  else if(path.endsWith('/trace'))data={items:[]};
  else if(path==='/tasks/'+tid)data=task;
  else if(path==='/conversations/'+cid)data={id:cid,title:'证照借用申请',activeTaskId:tid};
  return route.fulfill({status:path.endsWith('/actions')?202:200,json:{code:'OK',data,traceId:'test-trace'}});
 });
 await page.goto('/');await page.getByRole('button',{name:'证照借用申请',exact:true}).click();
 return actions;
}
test('confirmed card sends only server confirmation reference and task version',async({page})=>{
 const actions=await mockApi(page,{type:'FORM_PREVIEW',data:{confirmationId:'confirm-1',values:{destination:'日本'},fields:[{key:'destination',label:'目的地',editable:true}]}});
 await expect(page.getByText('日本',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'确认提交',exact:true}).click();
 await expect.poll(()=>actions.length).toBe(1);
 expect(actions[0]).toMatchObject({type:'CONFIRM',expectedTaskVersion:3,payload:{confirmationId:'confirm-1'}});
 expect(Object.keys(actions[0].payload)).toEqual(['confirmationId']);
});
test('editable metadata form collects only entered values and displays field errors',async({page})=>{
 const actions=await mockApi(page,{type:'FIELD_COLLECTION',data:{values:{destination:'日本'},fields:[{key:'destination',label:'目的地',editable:true},{key:'returnDate',label:'归还日期',type:'date',required:true}],fieldErrors:{returnDate:'请补充归还日期'}}});
 await expect(page.getByText('请补充归还日期')).toBeVisible();
 await page.getByLabel('归还日期',{exact:false}).fill('2026-12-22');
 await page.getByRole('button',{name:'更新资料',exact:true}).click();
 await expect.poll(()=>actions.length).toBe(1);
 expect(actions[0]).toMatchObject({type:'PROVIDE_FIELDS',payload:{componentId:'card-1',componentVersion:2,values:{destination:'日本',returnDate:'2026-12-22'}}});
});
test('excluding a todo changes preview scope without approving anything',async({page})=>{
 const actions=await mockApi(page,{type:'TODO_SELECTION',data:{items:[{id:'todo-1',title:'上海出差',businessNo:'TR001'},{id:'todo-2',title:'北京出差',businessNo:'TR002'}],totalCount:2,selectedIds:['todo-1','todo-2']}});
 await page.getByRole('checkbox',{name:'选择TR002'}).uncheck();
 await page.getByRole('button',{name:'预览审核范围'}).click();
 await expect.poll(()=>actions.length).toBe(1);
 expect(actions[0]).toMatchObject({type:'UPDATE_REVIEW',payload:{selectedTodoIds:['todo-1'],decision:'APPROVE'}});
 expect(actions.some(x=>x.type==='CONFIRM')).toBe(false);
});
test('stale confirmation card cannot trigger another action',async({page})=>{
 const actions=await mockApi(page,{type:'FORM_PREVIEW',status:'CONSUMED',data:{confirmationId:'confirm-old',values:{destination:'日本'},fields:[]}});
 await expect(page.getByRole('button',{name:'确认提交',exact:true})).toBeDisabled();
 expect(actions).toHaveLength(0);
});
