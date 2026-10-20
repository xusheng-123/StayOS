export interface RoomIssue {
 id:number; roomId:number; roomNumber:string; floor:number; roomTypeName:string; issueType:string; severity:string; status:string;
 description:string; notes:string; reportedBy:number; reporterName:string; reportedAt:string; handlerName:string|null;
 startedAt:string|null; resolvedAt:string|null; resolution:string; version:number
}
export interface IssueLog { id:number; issueId:number; action:string; fromStatus:string|null; toStatus:string; fromSeverity:string|null; toSeverity:string; operatorName:string; operatedAt:string; reason:string }
export interface MaintenanceStop { id:number; roomId:number; issueId:number|null; reason:string; stoppedAt:string; stoppedByName:string; resumedAt:string|null; resumedByName:string|null; resumeReason:string }
export const issueTypes:Record<string,string>={FACILITY:'房间设施',ELECTRICAL:'电器设备',PLUMBING:'供水及管道',SANITATION:'卫生环境',SAFETY:'安全问题',OTHER:'其他异常'}
export const severities:Record<string,string>={LOW:'一般',MEDIUM:'需要处理',CRITICAL:'严重 · 阻止新入住'}
export const issueStates:Record<string,string>={OPEN:'待处理',IN_PROGRESS:'处理中',RESOLVED:'已解决'}
export const issueActions:Record<string,string>={REPORT:'上报异常',START:'开始处理',RESOLVE:'解决异常',SEVERITY:'调整严重程度'}
