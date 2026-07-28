import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BusinessConfig,
  BusinessConfigUpdate,
  FlagDetail,
  FlagStatus,
  FlagSummary,
  FlagVisits,
  ImportPreview,
  ImportResult,
  OverviewStats,
  PageResponse,
  ScanSummary,
} from './api.models';

/** Every backend call the dashboard makes (§10). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private readonly http: HttpClient) {}

  getConfig(): Observable<BusinessConfig> {
    return this.http.get<BusinessConfig>('/v1/businesses/me/config');
  }

  updateConfig(update: BusinessConfigUpdate): Observable<BusinessConfig> {
    return this.http.put<BusinessConfig>('/v1/businesses/me/config', update);
  }

  listFlags(page: number, size: number, status: FlagStatus | null): Observable<PageResponse<FlagSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<PageResponse<FlagSummary>>('/v1/flags', { params });
  }

  getFlag(id: string): Observable<FlagDetail> {
    return this.http.get<FlagDetail>(`/v1/flags/${id}`);
  }

  /**
   * Reads a CSV's columns and first rows so the admin can map them. Imports
   * nothing.
   */
  previewImport(file: File): Observable<ImportPreview> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ImportPreview>('/v1/transactions/import/preview', form);
  }

  /**
   * Bulk import of a POS export (FR-1), using the confirmed column mapping.
   *
   * No Content-Type is set deliberately: the browser must add the multipart
   * boundary itself, and setting the header by hand omits it.
   */
  importTransactions(file: File, mapping: Record<string, string>): Observable<ImportResult> {
    const form = new FormData();
    form.append('file', file);
    Object.entries(mapping).forEach(([field, column]) => {
      // An unmapped optional field is omitted rather than sent empty, so the
      // server sees "not present" instead of "mapped to a column named ''".
      if (column) {
        form.append(field, column);
      }
    });
    return this.http.post<ImportResult>('/v1/transactions/import', form);
  }

  /** One flagged customer's recent visits (FR-7). */
  getFlagVisits(id: string): Observable<FlagVisits> {
    return this.http.get<FlagVisits>(`/v1/flags/${id}/visits`);
  }

  /**
   * Visit history for a whole page of flags in one request — calling the
   * single-flag endpoint per row would be an N+1 over HTTP.
   */
  getFlagVisitsBatch(ids: string[]): Observable<FlagVisits[]> {
    return this.http.get<FlagVisits[]>('/v1/flags/visits', {
      params: new HttpParams().set('ids', ids.join(',')),
    });
  }

  /** Every counter behind the overview screen, aggregated server-side (FR-7). */
  getOverview(): Observable<OverviewStats> {
    return this.http.get<OverviewStats>('/v1/stats/overview');
  }

  /** How many flagged customers have no contact details at all (FR-5). */
  getStats(): Observable<{ uncontactableOffers: number }> {
    return this.http.get<{ uncontactableOffers: number }>('/v1/flags/stats');
  }

  /** Runs the scan on demand so a demo doesn't wait for the nightly schedule. */
  runScan(): Observable<ScanSummary> {
    return this.http.post<ScanSummary>('/v1/admin/retention/run', {});
  }
}
