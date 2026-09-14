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
  /** Days after a delivered offer in which a purchase still counts as a return. */
  recoveryWindowDays: number;
  /** ISO 4217 code every amount is denominated in. Never converted. */
  currencyCode: string;
  timezone: string;
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
  recoveryWindowDays: number;
  currencyCode: string;
  timezone: string;
  defaultDealType: DealType;
  defaultDealValue: number;
}

/**
 * How far past their own threshold a quiet customer is. Mirrors
 * `com.lucklotter.domain.OverdueBand` — the boundaries live there and in the
 * overview aggregate's SQL, and the two have to move together.
 */
export type OverdueBand = 'JUST_PAST' | 'WELL_PAST' | 'LONG_OVERDUE';

/** The orders the flagged list can be read in — `FlagSort` on the server. */
export type FlagSort = 'PRIORITY' | 'OVERDUE' | 'VALUE' | 'RECENT';

/**
 * What the priority score is made of, each component clamped to 0-100 so no
 * single one can carry a customer to the top of the list.
 *
 * `history` is 50, not 0, for a customer who has never been offered anything:
 * that is absence of evidence, and scoring it zero would bury every new
 * customer under everyone who has ever answered an offer.
 */
export interface FlagPriority {
  risk: number;
  value: number;
  history: number;
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
  visitCount: number;
  totalSpend: number;
  averageTransactionValue: number;
  /** Null unless the POS export carried item names for this customer. */
  usualItem: string | null;
  /** Days past this customer's own threshold; null for a resolved flag. */
  daysOverdue: number | null;
  riskBand: OverdueBand | null;
  /** Offers sent to this customer before this one, and how many they answered. */
  priorInterventions: number;
  priorReturns: number;
  /** 0-100. 40% risk, 40% value, 20% history — not a model's prediction. */
  priorityScore: number;
  priority: FlagPriority;
  /** Plain language, derived from the state the row is actually in. */
  recommendedAction: string;
}

export interface FlagDetail {
  flagId: string;
  status: FlagStatus;
  flaggedAt: string;
  resolvedAt: string | null;
  customerId: string;
  customerRef: string;
  customerName: string | null;
  firstSeenAt: string;
  lastVisitAt: string | null;
  transactionCount: number;
  avgIntervalDays: number | null;
  contactable: boolean;
  avgIntervalDaysAtFlag: number;
  thresholdDaysApplied: number;
  /**
   * Last visit plus the cadence snapshotted at flagging — the date they were
   * due. Always earlier than the threshold that fired the flag, which is this
   * date scaled by the business's sensitivity setting.
   */
  expectedNextVisitAt: string | null;
  /** How far past that date they are now. Null when there is no date to measure from. */
  daysBeyondUsual: number | null;
  offerId: string | null;
  dealType: DealType | null;
  dealValue: number | null;
  offerStatus: OfferStatus | null;
  redemptionCode: string | null;
  offerFailureCode: OfferFailureCode | null;
  offerSentAt: string | null;
  /**
   * The first visit after the offer, inside the business's recovery window.
   * Null when the offer went unanswered — or was answered after the window
   * closed, which the dashboard's money does not count either.
   */
  returnedAt: string | null;
  returnAmount: number | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/**
 * Recovery carries its own denominator: "100%" over two offers and "68%" over
 * four hundred are different claims, and the card shows which one it is.
 *
 * The denominator is offers that were *delivered*, not flags raised. A flag
 * whose offer never reached the customer is not an attempt that failed, so it is
 * counted separately in `neverContacted` rather than folded into the rate.
 *
 * `percent` is null when nothing has been contacted yet — no rate yet, which is
 * not a rate of zero.
 */
export interface Recovery {
  contacted: number;
  returned: number;
  windowDays: number;
  percent: number | null;
  neverContacted: number;
}

/**
 * A financial figure and the sentence that says what it is made of.
 *
 * The definitions travel with the numbers rather than living in documentation:
 * a recovery rate whose denominator only its author knows is one nobody can
 * defend in a meeting.
 */
export interface RetentionImpact {
  currencyCode: string;
  recoveryWindowDays: number;
  contacted: number;
  returned: number;
  recoveryPercent: number | null;
  revenueFromReturnedCustomers: number;
  /** Null when any delivered offer was a free item — its cost is unknowable. */
  interventionCost: number | null;
  /** Null whenever the cost is unknown; a net figure on a partial cost flatters itself. */
  netValue: number | null;
  averageRecoveredCustomerValue: number | null;
  freeItemOffers: number;
  flaggedNeverContacted: number;
  definitions: {
    contacted: string;
    returned: string;
    revenueFromReturnedCustomers: string;
    interventionCost: string;
    netValue: string;
  };
}

export interface WeeklyPoint {
  weekStart: string;
  flagsRaised: number;
  customersRecovered: number;
}

export interface OverviewStats {
  customersTotal: number;
  customersMonitored: number;
  customersBelowThreshold: number;
  activeFlags: number;
  resolvedFlags: number;
  offersSent: number;
  offersNoContact: number;
  recovery: Recovery;
  /** Always 8 entries, oldest first, including weeks with no activity. */
  weeklySeries: WeeklyPoint[];
  comparison: PeriodComparison;
  statusBreakdown: StatusBreakdown;
  /** Empty when nobody is currently quiet. */
  overdueBuckets: OverdueBucket[];
  /** Drifting customers worth at least twice the average customer. */
  highValueDrifting: number;
  definitions: OverviewDefinitions;
}

/** Plain-language versions of the cards above, rendered beside them. */
export interface OverviewDefinitions {
  customersMonitored: string;
  customersBelowThreshold: string;
  recovery: string;
  statusBreakdown: string;
  highValueDrifting: string;
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

/**
 * The customer base, split four ways.
 *
 * A partition, not a selection: the four buckets are disjoint and add up to
 * `total`. A customer who returned and has since gone quiet counts as `drifting`
 * — both statements are true, and only one of them says what to do today.
 */
export interface StatusBreakdown {
  drifting: number;
  returned: number;
  onRhythm: number;
  notEnoughData: number;
  total: number;
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
 * What the file would do if it were imported now, counted against the mapping
 * the caller sent. Null until a complete mapping has been confirmed — before
 * that there is no honest way to know which column is the customer or the date.
 *
 * The counts come from the same row conversion the import runs, so they are a
 * statement about this file against this mapping rather than an estimate that
 * could disagree with the import's own result.
 */
export interface ImportPreviewStats {
  rowCount: number;
  validRows: number;
  invalidRowCount: number;
  /** The first few failures, by 1-based line in the file. */
  invalidSamples: ImportRowError[];
  uniqueCustomers: number;
  /** Date range of the rows that would import; null when none of them would. */
  earliest: string | null;
  latest: string | null;
  /** True when the file has more rows than can be imported in one go. */
  truncated: boolean;
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
  stats: ImportPreviewStats | null;
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
