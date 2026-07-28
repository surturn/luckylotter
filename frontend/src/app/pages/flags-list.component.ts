import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { FlagStatus, FlagSummary, PageResponse } from '../core/api.models';
import { StatusPillComponent } from '../shared/status-pill.component';

/**
 * The flagged-customer list (FR-7, US-2).
 *
 * Each row carries the evidence behind its own trigger — the customer's usual
 * gap and the threshold it crossed — so the admin can judge whether the flag
 * was fair instead of taking it on trust.
 */
@Component({
  selector: 'app-flags-list',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, StatusPillComponent],
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
      @for (option of filters; track option.value) {
        <button type="button"
                [class]="status() === option.value ? 'btn-primary' : 'btn-secondary'"
                [attr.aria-pressed]="status() === option.value"
                (click)="setStatus(option.value)">
          {{ option.label }}
        </button>
      }
    </div>

    <div class="card">
      @if (loading()) {
        <div style="padding:var(--space-4)">
          @for (row of [1,2,3,4,5]; track row) {
            <div class="skeleton" style="margin:14px 0"></div>
          }
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
                  <th scope="col">Last visit</th>
                  <th scope="col">Usual gap</th>
                  <th scope="col">Flagged after</th>
                  <th scope="col">Offer</th>
                  <th scope="col">Flag</th>
                  <th scope="col"><span class="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (flag of data.items; track flag.flagId) {
                  <tr>
                    <td class="mono">{{ flag.customerRef }}</td>
                    <td class="mono">{{ flag.lastVisitAt ? (flag.lastVisitAt | date: 'd MMM yyyy') : '—' }}</td>
                    <td class="mono">every {{ flag.avgIntervalDaysAtFlag | number: '1.0-1' }} days</td>
                    <td class="mono">{{ flag.thresholdDaysApplied | number: '1.0-1' }} quiet days</td>
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
    .filters button { min-height: 36px; padding: 6px 14px; font-size: var(--text-sm); }
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
  readonly scanning = signal(false);
  readonly error = signal<string | null>(null);
  readonly banner = signal<string | null>(null);
  readonly uncontactable = signal(0);
  readonly contactWarningHidden = signal(false);

  private readonly pageSize = 25;

  constructor() {
    this.load(0);
    this.loadStats();
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
        this.loadStats();
      },
      error: () => {
        this.scanning.set(false);
        this.error.set('The scan could not be started. Try again in a moment.');
      },
    });
  }

  private load(page: number): void {
    this.loading.set(true);
    this.api.listFlags(page, this.pageSize, this.status()).subscribe({
      next: (data) => {
        this.page.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load flagged customers. Refresh to try again.');
      },
    });
  }

  private loadStats(): void {
    this.api.getStats().subscribe({
      next: (stats) => this.uncontactable.set(stats.uncontactableOffers),
      // A missing counter is not worth an error banner over the main table.
      error: () => this.uncontactable.set(0),
    });
  }
}
