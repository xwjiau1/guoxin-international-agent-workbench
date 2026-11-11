export type Data = Record<string, any>;
export interface Actor { id:string; employeeNo:string; name:string; department:string; csrfToken:string }
export interface Component { componentId:string;taskId:string;type:string;version:number;status:string;data:Data;allowedActions?:string[] }
export interface Task extends Data { id:string;status:string;version:number;components?:Component[] }
export interface Message extends Data { id:string;role:string;content:string;components?:Component[] }
let csrf='';
export function setCsrf(value:string){csrf=value||''}
export class ApiError extends Error { constructor(public status:number, public detail:Data){super(detail.message||'请求未完成，请稍后重试')} }
export async function api<T=Data>(path:string, method='GET', body?:unknown):Promise<T>{
 const response=await fetch('/api/v1'+path,{method,credentials:'same-origin',headers:{...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'&&csrf?{'X-CSRF-Token':csrf}:{})},body:body?JSON.stringify(body):undefined});
 const value=await response.json().catch(()=>({message:response.status===502?'后端尚未启动，请先启动 Java 服务。':'服务响应异常'}));
 if(!response.ok)throw new ApiError(response.status,value);
 return value.data as T;
}
export const uuid=()=>crypto.randomUUID();
export const list=(value:any):Data[]=>Array.isArray(value)?value:value?.items||[];
export const statusLabel=(value:string)=>({RUNNING:'正在处理',WAITING_INPUT:'等待补充',WAITING_CONFIRMATION:'等待确认',DRAFT_SAVED:'已存草稿',PAUSED:'已暂停',EXECUTING:'正在办理',RECONCILING:'核实办理结果',SUCCEEDED:'办理完成',PARTIAL_SUCCEEDED:'部分完成',FAILED_RECOVERABLE:'可以恢复',FAILED_FINAL:'办理失败',CANCELLED:'已取消',PENDING:'待审批',APPROVED:'已同意',REJECTED:'已驳回',IN_APPROVAL:'审批中',DRAFT:'草稿',ACTIVE:'可操作',CONSUMED:'已执行',SUPERSEDED:'已更新',EXPIRED:'已过期'} as Record<string,string>)[value]||value;
