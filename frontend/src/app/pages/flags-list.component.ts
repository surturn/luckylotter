import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { FlagStatus, FlagSummary, FlagVisits, PageResponse } from '../core/api.models';
import { StatusPillComponent } from '../shared/status-pill.component';
import { VisitSparklineComponent } from '../shared/visit-sparkline.component';

/**
 * The flagged-customer list (FR-7, US-2).
 *
 * Each row carries the evidence behind its own trigger — the customer's usual
 * gap, the threshold it crossed, and the visit rhythm those numbers came from —
 * so the admin can judge whether the flag was fair instead of taking it on
 * trust.
 */
@Component({
  selector: 'app-flags-list',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, StatusPillComponent, VisitSparklineComponent],
  template: `
    <div class="head">
      <div>
        <h1>Flagged customers</h1>
        <p class="muted small" style="margin:4px 0 0">
          Regulars whose visit pattern has broken, and the win-back offer generated for each.
        </p>
      </div>
      <button type="button" class="btn-primary" (click)="runScan()" [disabled]="scanning()">
        {{ scanning() ? 'Scanning…' : 'Run scan now' }}
      </button>
    </div>

    @if (banner(); as message) {
      <div class="banner banner-success" role="status">
        <span>{{ message }}</span>
        <button type="button" class="banner-dismiss" aria-label="Dismiss this message"
                (click)="banner.set(null)">&times;</button>
      </div>
    }
    @if (error(); as message) {
      <div class="banner banner-error" role="alert">
        <span>{{ message }}</span>
        <button type="button" class="banner-dismiss" aria-label="Dismiss this message"
                (click)="error.set(null)">&times;</button>
      </div>
    }

    @if (uncontactable() > 0 && !contactWarningHidden()) {
      <div class="banner banner-warning" role="status">
        <span>
          <strong>{{ uncontactable() }}</strong>
          {{ uncontactable() === 1 ? 'offer has' : 'offers have' }} nowhere to go — those customers have no
          email or phone in your POS data, so nothing can be sent to them.
        </span>
        <!-- Dismissible, but it comes back on reload: the gap is still real,
             and hiding it permanently would quietly bury the coverage problem. -->
        <button type="button" class="banner-dismiss" aria-label="Hide this notice for now"
                (click)="contactWarningHidden.set(true)">&times;</button>
      </div>
    }

    <div class="filters" role="group" aria-label="Filter by status">
      @for (option of filters; track option.label) {
        <button type="button"
                [class]="status() === option.value ? 'btn-primary' : 'btn-secondary'"
                [attr.aria-pressed]="status() === option.value"
                (click)="setStatus(option.value)">
          {{ option.label }}
          @if (countFor(option.value) !== null) {
            <span class="count">{{ countFor(option.value) }}</span>
          }
        </button>
      }
    </div>

    <div class="card">
      @if (loading()) {
        <div class="loading">
          @for (row of [1,2,3,4,5]; track row) {
            <div class="skeleton"></div>
          }
        </div>
      } @else if (loadFailed()) {
        <div class="state">
          <p style="margin:0 0 var(--space-3)"><strong>Couldn't load flagged customers.</strong></p>
          <button type="button" class="btn-secondary" (click)="load(0)">Try again</button>
        </div>
      } @else {
        @if (page(); as data) {
        @if (data.items.length === 0) {
          <div class="state">
            <p style="margin:0 0 var(--space-2)"><strong>No flagged customers{{ status() ? ' with this status' : '' }}.</strong></p>
            <p class="small" style="margin:0">
              Either everyone is visiting on their usual rhythm, or the scan hasn't run since they went quiet.
              Use <em>Run scan now</em> to check immediately.
            </p>
          </div>
        } @else {
          <div class="table-scroll">
            <table>
              <caption class="visually-hidden">Customers flagged as at risk</caption>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col">Visit rhythm</th>
                  <th scope="col">Last visit</th>
                  <th scope="col" class="numeric">Usual gap</th>
                  <th scope="col" class="numeric">Flagged after</th>
                  <th scope="col">Offer</th>
                  <th scope="col">Flag</th>
                  <th scope="col"><span class="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (flag of data.items; track flag.flagId) {
                  <tr>
                    <td class="mono">{{ flag.customerRef }}</td>
                    <td class="rhythm">
                      @if (visitsFor(flag.flagId); as history) {
                        <app-visit-sparkline [visits]="history.visits" [flaggedAt]="history.flaggedAt" />
                      } @else {
                        <span class="skeleton rhythm-placeholder"></span>
                      }
                    </td>
                    <td class="mono">{{ flag.lastVisitAt ? (flag.lastVisitAt | date: 'd MMM yyyy') : '—' }}</td>
                    <td class="mono numeric">every {{ flag.avgIntervalDaysAtFlag | number: '1.0-0' }} days</td>
                    <!-- Rounded so the column scans; the exact value stays in
                         the tooltip, since it is what the threshold actually
                         was. -->
                    <td class="mono numeric"
                        [title]="(flag.thresholdDaysApplied | number: '1.1-1') + ' quiet days'">
                      {{ flag.thresholdDaysApplied | number: '1.0-0' }} quiet days
                    </td>
                    <td><app-status-pill [status]="flag.offerStatus" /></td>
                    <td><app-status-pill [status]="flag.status" /></td>
                    <td><a [routerLink]="['/flags', flag.flagId]">View</a></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          @if (data.totalPages > 1) {
            <div class="pager">
              <button type="button" class="btn-secondary" [disabled]="data.page === 0"
                      (click)="goTo(data.page - 1)">Previous</button>
              <span class="small muted">Page {{ data.page + 1 }} of {{ data.totalPages }} · {{ data.totalItems }} flags</span>
              <button type="button" class="btn-secondary" [disabled]="data.page + 1 >= data.totalPages"
                      (click)="goTo(data.page + 1)">Next</button>
            </div>
          }
        }
        }
      }
    </div>
  `,
  styles: [`
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
      margin-bottom: var(--space-4);
    }
    .filters { display: flex; gap: var(--space-2); margin-bottom: var(--space-4); flex-wrap: wrap; }
    .filters button { min-height: 36px; padding: 0 var(--space-3); font-size: var(--text-sm); }
    .count {
      font-family: var(--font-data);
      font-variant-numeric: tabular-nums;
      font-size: var(--text-xs);
      padding: 1px 6px;
      border-radius: var(--radius-full);
      background: rgba(15, 23, 42, 0.08);
    }
    .btn-primary .count { background: rgba(255, 255, 255, 0.22); }
    /* Sticky header: scrolling a long list shouldn't cost you the column
       names. Needs its own background, or rows show through it. */
    .table-scroll { max-height: 65vh; overflow: auto; }
    thead th { position: sticky; top: 0; z-index: 1; }
    tbody tr:nth-child(even) { background: var(--neutral-50); }
    tbody tr:hover { background: var(--primary-50); }
    .rhythm { padding-top: var(--space-2); padding-bottom: var(--space-2); }
    .rhythm-placeholder { display: inline-block; width: 132px; height: 10px; }
    .loading { padding: var(--space-4); display: grid; gap: var(--space-5); }
    .pager {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4);
      border-top: 1px solid var(--border);
    }
  `],
})
export class FlagsListComponent {
  private readonly api = inject(ApiService);

  readonly filters: { label: string; value: FlagStatus | null }[] = [
    { label: 'All', value: null },
    { label: 'Still quiet', value: 'ACTIVE' },
    { label: 'Came back', value: 'RESOLVED' },
  ];

  readonly page = signal<PageResponse<FlagSummary> | null>(null);
  readonly status = signal<FlagStatus | null>(null);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly scanning = signal(false);
  readonly error = signal<string | null>(null);
  readonly banner = signal<string | null>(null);
  readonly uncontactable = signal(0);
  readonly contactWarningHidden = signal(false);

  /** Visit history per flag, fetched a page at a time. */
  private readonly visits = signal<Map<string, { visits: string[]; flaggedAt: string }>>(new Map());

  /**
   * Tab counts come from the aggregates endpoint. Counting the loaded page
   * would report "25" on every filter once there are more than 25 flags.
   */
  private readonly totals = signal<{ active: number; resolved: number } | null>(null);

  private readonly pageSize = 25;

  private readonly counts = computed(() => {
    const totals = this.totals();
    return totals === null
      ? null
      : { all: totals.active + totals.resolved, ACTIVE: totals.active, RESOLVED: totals.resolved };
  });

  constructor() {
    this.load(0);
    this.loadTotals();
  }

  countFor(status: FlagStatus | null): number | null {
    const counts = this.counts();
    if (counts === null) {
      return null;
    }
    return status === null ? counts.all : counts[status];
  }

  visitsFor(flagId: string): { visits: string[]; flaggedAt: string } | null {
    return this.visits().get(flagId) ?? null;
  }

  setStatus(status: FlagStatus | null): void {
    this.status.set(status);
    this.load(0);
  }

  goTo(page: number): void {
    this.load(page);
  }

  runScan(): void {
    this.scanning.set(true);
    this.banner.set(null);
    this.error.set(null);
    this.api.runScan().subscribe({
      next: (summary) => {
        this.scanning.set(false);
        this.banner.set(
          summary.flagged === 0
            ? `Scan complete — checked ${summary.scanned} quiet ${summary.scanned === 1 ? 'customer' : 'customers'}, none newly at risk.`
            : `Scan complete — ${summary.flagged} newly flagged, ${summary.skipped} still within their usual gap.`
        );
        this.load(0);
        this.loadTotals();
      },
      error: () => {
        this.scanning.set(false);
        this.error.set('The scan could not be started. Try again in a moment.');
      },
    });
  }

  load(page: number): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.api.listFlags(page, this.pageSize, this.status()).subscribe({
      next: (data) => {
        this.page.set(data);
        this.loading.set(false);
        this.loadVisits(data.items.map((flag) => flag.flagId));
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  /** One request for the whole page, not one per row. */
  private loadVisits(flagIds: string[]): void {
    if (flagIds.length === 0) {
      return;
    }
    this.api.getFlagVisitsBatch(flagIds).subscribe({
      next: (histories) => {
        const next = new Map(this.visits());
        histories.forEach((history: FlagVisits) =>
          next.set(history.flagId, {
            visits: history.visits.map((visit) => visit.occurredAt),
            flaggedAt: history.flaggedAt,
          })
        );
        this.visits.set(next);
      },
      // The rhythm column is supporting evidence. Losing it shouldn't put an
      // error banner over a table that loaded perfectly well.
      error: () => undefined,
    });
  }

  private loadTotals(): void {
    this.api.getOverview().subscribe({
      next: (stats) => {
        this.totals.set({ active: stats.activeFlags, resolved: stats.resolvedFlags });
        this.uncontactable.set(stats.offersNoContact);
      },
      error: () => this.totals.set(null),
    });
  }
}
