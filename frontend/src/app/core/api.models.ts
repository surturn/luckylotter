/**
 * Mirrors the backend DTOs in `com.lucklotter.web.dto`.
 *
 * Kept as a hand-written mirror rather than generated: the API is small, and
 * these types are the place where a field the dashboard depends on is named
 * once. If a field here stops matching the backend, the compiler says so at the
 * point of use.
 */

export type FlagStatus = 'ACTIVE' | 'RESOLVED';

/**
 * `NO_CONTACT` is terminal — the customer has no email and no phone (FR-5).
 * `SUPPRESSED_BUDGET` is terminal too: the customer was flagged, but the
 * business's offer cap for the window was already spent, so nothing was sent
 * (FR-4).
 */
export type OfferStatus = 'PENDING' | 'SENT' | 'FAILED' | 'NO_CONTACT' | 'SUPPRESSED_BUDGET';

export type DealType = 'PERCENT_OFF' | 'FIXED_AMOUNT_OFF' | 'FREE_ITEM';

/**
 * Mirrors `com.lucklotter.domain.OfferFailureCode` — a closed set, deliberately
 * carrying no provider text (NFR-4).
 */
export type OfferFailureCode =
  | 'MISSING_CONTACT_DETAILS'
  | 'INVALID_EMAIL_ADDRESS'
  | 'INVALID_PHONE_NUMBER'
  | 'SENDER_TIMEOUT'
  | 'SENDER_UNAVAILABLE'
  | 'SENDER_REJECTED'
  | 'UNKNOWN_ERROR';

export interface LoginResponse {
  token: string;
  expiresAt: string;
  businessId: string;
  businessName: string;
}

export interface BusinessConfig {
  name: string;
  sensitivityMultiplier: number;
  minThresholdDays: number;
  maxThresholdDays: number;
  offerCooldownDays: number;
  offerCooldownMultiplier: number;
  offerCapPerWindow: number;
  offerBudgetWindowDays: number;
  defaultDealType: DealType;
  defaultDealValue: number;
  /** Read-only: a fixed system constant, not per-business config (FR-2). */
  minTransactions: number;
}

export interface BusinessConfigUpdate {
  sensitivityMultiplier: number;
  minThresholdDays: number;
  maxThresholdDays: number;
  offerCooldownDays: number;
  offerCooldownMultiplier: number;
  offerCapPerWindow: number;
  offerBudgetWindowDays: number;
  defaultDealType: DealType;
  defaultDealValue: number;
}

export interface FlagSummary {
  flagId: string;
  customerId: string;
  customerRef: string;
  /** Null when the POS export carried no name for this customer. */
  customerName: string | null;
  status: FlagStatus;
  lastVisitAt: string | null;
  flaggedAt: string;
  avgIntervalDaysAtFlag: number;
  thresholdDaysApplied: number;
  contactable: boolean;
  dealType: DealType | null;
  dealValue: number | null;
  offerStatus: OfferStatus | null;
  /** Code the customer quotes to claim the offer; null for offers issued before codes existed. */
  redemptionCode: string | null;
  offerSentAt: string | null;
}

export interface FlagDetail {
  flagId: string;
  status: FlagStatus;
  flaggedAt: string;
  resolvedAt: string | null;
  customerId: string;
  customerRef: string;
  firstSeenAt: string;
  lastVisitAt: string | null;
  transactionCount: number;
  avgIntervalDays: number | null;
  contactable: boolean;
  avgIntervalDaysAtFlag: number;
  thresholdDaysApplied: number;
  offerId: string | null;
  dealType: DealType | null;
  dealValue: number | null;
  offerStatus: OfferStatus | null;
  redemptionCode: string | null;
  offerFailureCode: OfferFailureCode | null;
  offerSentAt: string | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/**
 * Recovery carries its own denominator: "100%" over two flags and "68%" over
 * four hundred are different claims, and the card shows which one it is.
 * `percent` is null when there are no flags at all — no rate yet, which is not
 * a rate of zero.
 */
export interface RecoveryRate {
  recovered: number;
  totalFlags: number;
  percent: number | null;
}

export interface WeeklyPoint {
  weekStart: string;
  flagsRaised: number;
  customersRecovered: number;
}

export interface OverviewStats {
  customersMonitored: number;
  customersBelowThreshold: number;
  activeFlags: number;
  resolvedFlags: number;
  offersSent: number;
  offersNoContact: number;
  recoveryRate: RecoveryRate;
  /** Always 8 entries, oldest first, including weeks with no activity. */
  weeklySeries: WeeklyPoint[];
  comparison: PeriodComparison;
  statusBreakdown: StatusBreakdown;
  /** Empty when nobody is currently quiet. */
  overdueBuckets: OverdueBucket[];
}

/**
 * This 8-week period against the previous one.
 *
 * Only covers things that happened at a point in time. There is deliberately no
 * comparison for `customersMonitored` or `activeFlags`: those describe how
 * things stand now, and nothing records how they stood eight weeks ago, so a
 * trend arrow beside them would be invented rather than measured.
 *
 * A null change means the previous period had nothing to compare against —
 * render it as "no earlier data", never as 0% or +100%.
 */
export interface PeriodComparison {
  flagsRaisedNow: number;
  flagsRaisedBefore: number;
  flagsRaisedChangePercent: number | null;
  recoveredNow: number;
  recoveredBefore: number;
  recoveredChangePercent: number | null;
  offersSentNow: number;
  offersSentBefore: number;
  offersSentChangePercent: number | null;
  recoveryPercentNow: number | null;
  recoveryPercentBefore: number | null;
  recoveryPercentChange: number | null;
}

export interface StatusBreakdown {
  cameBack: number;
  stillQuiet: number;
  notEnoughData: number;
}

/**
 * Severity spread of the currently-quiet customers.
 *
 * Not "reasons for being flagged" — there is exactly one trigger, a break in
 * the customer's own rhythm, so the honest breakdown is how far past their own
 * threshold each one is, expressed as a multiple of it.
 */
export interface OverdueBucket {
  bucket: 'JUST_PAST' | 'WELL_PAST' | 'LONG_OVERDUE';
  customers: number;
}

export interface VisitPoint {
  occurredAt: string;
}

/** Real transaction timestamps — never interpolated from an average. */
export interface FlagVisits {
  flagId: string;
  flaggedAt: string;
  visits: VisitPoint[];
}

export interface ScanSummary {
  businessId: string;
  scanned: number;
  flagged: number;
  skipped: number;
  errored: number;
}

/**
 * A CSV's shape before anything is imported. `suggested` maps our field names
 * to the caller's column names — a starting point for the mapping form, never
 * applied without confirmation.
 */
export interface ImportPreview {
  columns: string[];
  sampleRows: string[][];
  suggested: Record<string, string>;
}

export interface ImportRowError {
  /** 1-based line in the uploaded file, so it can be found in a spreadsheet. */
  line: number;
  message: string;
}

/** Duplicates are replays, not failures — they are reported separately (NFR-3). */
export interface ImportResult {
  totalRows: number;
  imported: number;
  duplicates: number;
  failed: number;
  errors: ImportRowError[];
}
