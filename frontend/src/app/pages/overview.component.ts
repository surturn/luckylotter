import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { OverviewStats } from '../core/api.models';
import { WeeklyChartComponent } from '../shared/weekly-chart.component';

/**
 * The landing screen after login (FR-7).
 *
 * <p>Every number here is computed server-side by `/v1/stats/overview` — one
 * request, one pass over the tables. Counting a paginated flag list on the
 * client would either count a single page or scan every page to produce an
 * integer.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [RouterLink, DecimalPipe, WeeklyChartComponent],
  template: `
    <div class="head">
      <div>
        <h1>Overview</h1>
        <p class="muted small" style="margin:4px 0 0">
          How many of your regulars are drifting, and how many came back after an offer.
        </p>
      </div>
      <a routerLink="/flags" class="btn-secondary link-btn">See flagged customers</a>
    </div>

    @if (loading()) {
      <div class="cards">
        @for (card of [1,2,3,4]; track card) {
          <div class="card stat">
            <div class="skeleton" style="width:60%"></div>
            <div class="skeleton" style="height:28px;margin:var(--space-3) 0"></div>
            <div class="skeleton" style="width:85%"></div>
          </div>
        }
      </div>
    } @else if (error()) {
      <div class="banner banner-error" role="alert">
        <span>{{ error() }}</span>
        <button type="button" class="btn-secondary retry" (click)="load()">Try again</button>
      </div>
    } @else {
      <!-- Nested, not an else-if with an alias: Angular allows the "as" alias
           on a plain if only. -->
      @if (stats(); as data) {
      <!-- The headline gets its own full-width row: the only number here that
           says whether any of this is working, and the one that needs room to
           carry its denominator. Sharing a row with the others left a hole in
           the grid, since a 2-wide card doesn't divide into the remaining three. -->
      <div class="hero">
        <div class="card stat stat-primary">
          <span class="label">Came back after an offer</span>
          @if (data.recoveryRate.percent === null) {
            <span class="value">—</span>
            <span class="sub">No customers have been flagged yet, so there's nothing to measure.</span>
          } @else {
            <span class="value">{{ data.recoveryRate.percent | number: '1.0-1' }}%</span>
            <span class="sub">
              {{ data.recoveryRate.recovered | number }} of
              {{ data.recoveryRate.totalFlags | number }}
              flagged {{ data.recoveryRate.totalFlags === 1 ? 'customer' : 'customers' }} visited again.
            </span>
          }
        </div>
      </div>

      <div class="cards">
        <div class="card stat">
          <span class="label">Customers monitored</span>
          <span class="value">{{ data.customersMonitored | number }}</span>
          <span class="sub">
            {{ data.customersBelowThreshold | number }} more seen too few times to have a rhythm yet.
          </span>
        </div>

        <div class="card stat">
          <span class="label">Currently quiet</span>
          <span class="value">{{ data.activeFlags | number }}</span>
          <span class="sub">Flagged and not back yet.</span>
        </div>

        <div class="card stat">
          <span class="label">Offers sent</span>
          <span class="value">{{ data.offersSent | number }}</span>
          <span class="sub">
            @if (data.offersNoContact > 0) {
              {{ data.offersNoContact | number }} more are waiting on contact details.
            } @else {
              Every offer generated so far had somewhere to go.
            }
          </span>
        </div>
      </div>

      <section class="card chart-card">
        <h2>The last 8 weeks</h2>
        <p class="muted small" style="margin:4px 0 var(--space-4)">
          Flags raised each week, against customers who came back after being flagged.
        </p>
        <app-weekly-chart [points]="data.weeklySeries" />
      </section>
      }
    }
  `,
  styles: [`
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
      margin-bottom: var(--space-5);
    }
    .link-btn {
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      min-height: var(--control-height);
      padding: 0 var(--space-4);
      border-radius: var(--radius-sm);
      font-size: var(--text-base);
      font-weight: 500;
    }
    .hero { margin-bottom: var(--space-4); }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--space-4);
      margin-bottom: var(--space-5);
    }
    .stat {
      padding: var(--space-5);
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      min-height: 128px;
    }
    .stat-primary {
      background: var(--primary-600);
      border-color: var(--primary-700);
      color: #fff;
      min-height: 0;
      /* Value and supporting line sit side by side, so a full-width hero
         doesn't become a tall empty band. */
      display: grid;
      grid-template-columns: auto 1fr;
      grid-template-areas: 'label label' 'value sub';
      align-items: baseline;
      column-gap: var(--space-4);
      row-gap: var(--space-2);
    }
    .stat-primary .label { grid-area: label; }
    .stat-primary .value { grid-area: value; }
    .stat-primary .sub { grid-area: sub; }
    .stat-primary .label,
    .stat-primary .sub { color: rgba(255, 255, 255, 0.82); }
    .label {
      font-size: var(--text-sm);
      font-weight: 500;
      color: var(--text-muted);
    }
    .value {
      font-family: var(--font-data);
      font-variant-numeric: tabular-nums;
      font-size: 2rem;
      font-weight: 600;
      line-height: 1.15;
      letter-spacing: -0.02em;
    }
    .stat-primary .value { font-size: 2.75rem; }
    .sub { font-size: var(--text-sm); color: var(--text-muted); }
    .chart-card { padding: var(--space-5); }
    .retry { flex: none; min-height: 32px; padding: 0 var(--space-3); font-size: var(--text-sm); }

    @media (max-width: 640px) {
      .stat-primary {
        grid-template-columns: 1fr;
        grid-template-areas: 'label' 'value' 'sub';
      }
    }
  `],
})
export class OverviewComponent {
  private readonly api = inject(ApiService);

  readonly stats = signal<OverviewStats | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.getOverview().subscribe({
      next: (data) => {
        this.stats.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load your figures.');
      },
    });
  }
}
