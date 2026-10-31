// Cashier shifts, business days and frozen daily reports (V2.1).
export type ShiftStatus = 'OPEN' | 'SUBMITTED' | 'CONFIRMED'
export const shiftStatusNames: Record<ShiftStatus, string> = { OPEN: '进行中', SUBMITTED: '待确认', CONFIRMED: '已确认' }

export type ShiftMoney = { receipts: string; refunds: string; expectedCash: string }
export type ShiftView = {
  id: number; operatorName: string; username: string; businessDate: string; status: ShiftStatus
  openedFloat: string; openedAt: string; countedCash: string | null; differenceReason: string | null
  submittedAt: string | null; confirmedByName: string | null; confirmedAt: string | null; version: number
  money: ShiftMoney
}

export type BusinessDayStatus = 'OPEN' | 'CLOSED'
export type BusinessDayView = {
  businessDate: string; status: BusinessDayStatus; cutoff: string; closedAt: string | null
  closedByName: string | null; hasReport: boolean; current: boolean
}
export type CloseResultView = { runId: number; businessDate: string; status: string; failureReason: string | null; stepsDone: number }

// The snapshot freezes report numbers at close time; later corrections land on the current day and are traced in laterAdjustments.
export type DailyReportItem = { id: number; businessDate: string; snapshot: Record<string, unknown>; createdByName: string; createdAt: string }
