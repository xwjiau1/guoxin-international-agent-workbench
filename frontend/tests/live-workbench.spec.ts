import {test,expect} from '@playwright/test';
import type {Page,APIRequestContext} from '@playwright/test';

// These tests perform REAL writes through the Docker-hosted backend and database.
// They never intercept or mock requests. Opt in only against local-demo data.
test.skip(process.env.RUN_LIVE_E2E!=='1','Set RUN_LIVE_E2E=1 against an already-running Docker local-demo stack.');
test.describe.configure({mode:'serial',timeout:480000});

async function login(page:Page){
 await page.goto('/');
 await page.getByRole('button',{name:'进入工作台'}).click();
 await expect(page.getByRole('button',{name:'开启新对话'})).toBeVisible({timeout:20000});
}
async function settle(request:APIRequestContext,taskId:string){
 let task:any;
 await expect.poll(async()=>{
  const response=await request.get('/api/v1/tasks/'+taskId);
  expect(response.ok()).toBeTruthy();task=(await response.json()).data;
  return !task.activeRunId&&!['RUNNING','EXECUTING'].includes(task.status);
 },{timeout:210000,intervals:[500,1000,2000]}).toBe(true);
 expect(task.status,JSON.stringify(task)).not.toMatch(/^FAILED/);
 return task;
}
async function message(page:Page,text:string){
 const box=page.getByRole('textbox',{name:'发送消息给智能小秘'});
 await box.fill(text);
 const [response]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/messages')),page.getByRole('button',{name:'发送消息',exact:true}).click()]);
 expect(response.status()).toBe(202);
 const accepted=(await response.json()).data;
 return settle(page.request,accepted.taskId);
}
async function action(page:Page,button:ReturnType<Page['getByRole']>,taskId:string){
 await expect(button).toBeEnabled({timeout:20000});
 const [response]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/actions')),button.click()]);
 expect(response.status(),await response.text()).toBe(202);
 return settle(page.request,taskId);
}
function futureDates(){
 const parts=new Intl.DateTimeFormat('en-US',{timeZone:'Asia/Shanghai',year:'numeric',month:'numeric'}).formatToParts(new Date());
 const currentYear=Number(parts.find(x=>x.type==='year')!.value),currentMonth=Number(parts.find(x=>x.type==='month')!.value);
 const year=currentYear+(currentMonth===12?1:0),month=String(currentMonth%12+1).padStart(2,'0');
 return [`${year}-${month}-15`,`${year}-${month}-20`,`${year}-${month}-21`];
}

test('Docker real API: credential collection → incomplete draft → refresh → resume → submit',async({page},testInfo)=>{
 await login(page);
 let task=await message(page,'我想借护照去日本旅游，请帮我准备申请。');
 expect(task.status).toBe('WAITING_INPUT');
 const collection=page.locator('.card-container').getByRole('region',{name:'补充申请资料'}).last();
 await expect(collection).toBeVisible({timeout:20000});
 task=await action(page,collection.getByRole('button',{name:'保存当前草稿'}),task.id);
 expect(task.status).toBe('DRAFT_SAVED');expect(task.draftId).toBeTruthy();
 const draftResponse=await page.request.get('/api/v1/drafts/'+task.draftId);
 expect(draftResponse.ok()).toBeTruthy();expect((await draftResponse.json()).data.taskId).toBe(task.id);
 await page.reload();
 await expect(page.locator('.task-status')).toContainText('已存草稿',{timeout:20000});
 task=await action(page,page.locator('.task-strip').getByRole('button',{name:'继续',exact:true}),task.id);
 expect(task.status).toBe('WAITING_INPUT');
 const card=page.locator('.card-container').getByRole('region',{name:'补充申请资料'}).last();
 const [departure,back,returned]=futureDates();
 await card.getByLabel('出发日期',{exact:false}).fill(departure);
 await card.getByLabel('返程日期',{exact:false}).fill(back);
 await card.getByLabel('归还日期',{exact:false}).fill(returned);
 task=await action(page,card.getByRole('button',{name:'更新资料',exact:true}),task.id);
 expect(task.status).toBe('WAITING_CONFIRMATION');
 const preview=page.locator('.card-container').getByRole('region',{name:'确认申请信息'}).last();
 await expect(preview).toContainText('日本');
 task=await action(page,preview.getByRole('button',{name:'确认提交',exact:true}),task.id);
 expect(task.status).toBe('SUCCEEDED');
 const receipt=task.components.find((x:any)=>x.type==='BUSINESS_RECEIPT')?.data;
 expect(receipt.businessNo).toBeTruthy();expect(receipt.processNo).toBeTruthy();
 expect(receipt.status||receipt.businessStatus).toBe('IN_APPROVAL');
 await expect(page.locator('.card-container').getByRole('region',{name:'业务办理回执'}).last()).toContainText(receipt.businessNo,{timeout:20000});
 await page.locator('.chat-scroll').evaluate(e=>e.scrollTop=e.scrollHeight);
 await page.waitForTimeout(300);
 await page.screenshot({path:testInfo.outputPath('credential-submitted.png'),fullPage:false});
 const trace=await page.request.get('/api/v1/tasks/'+task.id+'/trace?limit=100');
 expect((await trace.json()).data.items.some((x:any)=>x.type==='tool.completed')).toBeTruthy();
});

test('Docker real API: own todos → select one → preview → confirm approval',async({page},testInfo)=>{
 await login(page);await page.getByRole('button',{name:'开启新对话'}).click();
 let task=await message(page,'查询所有当前分配给我的待审批出差待办。');
 const selection=page.locator('.card-container').getByRole('region',{name:'选择待办范围'}).last();
 await expect(selection).toBeVisible({timeout:20000});
 const checkboxes=selection.getByRole('checkbox');const count=await checkboxes.count();expect(count).toBeGreaterThan(0);
 for(let i=1;i<count;i++)await checkboxes.nth(i).uncheck();
 const selected=(task.components.find((x:any)=>x.type==='TODO_SELECTION')?.data.items||[])[0];
 expect(selected).toBeTruthy();
 task=await action(page,selection.getByRole('button',{name:'预览审核范围'}),task.id);
 const preview=page.locator('.card-container').getByRole('region',{name:'确认审核范围'}).last();
 await expect(preview).toBeVisible({timeout:20000});
 expect(task.components.find((x:any)=>x.type==='APPROVAL_PREVIEW').data.items).toHaveLength(1);
 task=await action(page,preview.getByRole('button',{name:'确认同意 1 条'}),task.id);
 expect(task.status).toBe('SUCCEEDED');
 const receipt=task.components.find((x:any)=>x.type==='BUSINESS_RECEIPT').data;
 expect(receipt.items).toHaveLength(1);expect(receipt.items[0].success).toBe(true);
 expect(receipt.items[0].todoId).toBe(selected.todoId||selected.id);
 await expect(page.locator('.card-container').getByRole('region',{name:'业务办理回执'}).last()).toBeVisible({timeout:20000});
 await page.locator('.chat-scroll').evaluate(e=>e.scrollTop=e.scrollHeight);
 await page.waitForTimeout(300);
 await page.screenshot({path:testInfo.outputPath('todo-approved.png'),fullPage:false});
});
