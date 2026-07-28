import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BusinessConfig,
  BusinessConfigUpdate,
  FlagDetail,
  FlagStatus,
  FlagSummary,
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
