/**
 * Mirrors the backend DTOs in `com.lucklotter.web.dto`.
 *
 * Kept as a hand-written mirror rather than generated: the API is small, and
 * these types are the place where a field the dashboard depends on is named
 * once. If a field here stops matching the backend, the compiler says so at the
 * point of use.
 */

export type FlagStatus = 'ACTIVE' | 'RESOLVED';

/** `NO_CONTACT` is terminal — the customer has no email and no phone (FR-5). */
export type OfferStatus = 'PENDING' | 'SENT' | 'FAILED' | 'NO_CONTACT';

export type DealType = 'PERCENT_OFF' | 'FIXED_AMOUNT_OFF' | 'FREE_ITEM';

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
  defaultDealType: DealType;
  defaultDealValue: number;
  /** Read-only: a fixed system constant, not per-business config (FR-2). */
  minTransactions: number;
}

export interface BusinessConfigUpdate {
  sensitivityMultiplier: number;
  minThresholdDays: number;
  maxThresholdDays: number;
  defaultDealType: DealType;
  defaultDealValue: number;
}

export interface FlagSummary {
  flagId: string;
  customerId: string;
  customerRef: string;
  status: FlagStatus;
  lastVisitAt: string | null;
  flaggedAt: string;
  avgIntervalDaysAtFlag: number;
  thresholdDaysApplied: number;
  contactable: boolean;
  dealType: DealType | null;
  dealValue: number | null;
  offerStatus: OfferStatus | null;
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
  offerFailureCode: string | null;
  offerSentAt: string | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ScanSummary {
  businessId: string;
  scanned: number;
  flagged: number;
  skipped: number;
  errored: number;
}
