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
              <!-- Only claimed when the earlier period had enough flags to have
                   had a rate at all. A jump "from 0%" over two flags is a fact
                   about the sample size, not about retention. -->
              @if (data.comparison.recoveryPercentChange !== null
                    && data.comparison.flagsRaisedBefore >= 5) {
                <span class="hero-delta">
                  {{ data.comparison.recoveryPercentChange! > 0 ? '↑' : '↓' }}
                  {{ abs(data.comparison.recoveryPercentChange!) | number: '1.0-1' }}
                  points vs the previous 8 weeks.
                </span>
              }
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
          <!-- The only stat card with a trend line. "Monitored" and "currently
               quiet" are counts of how things stand right now; nothing records
               how they stood eight weeks ago, so an arrow beside them would be
               reconstructed rather than measured. -->
          @if (data.comparison.offersSentChangePercent !== null) {
            <span class="delta" [class.down]="data.comparison.offersSentChangePercent! < 0">
              {{ data.comparison.offersSentChangePercent! > 0 ? '↑' : '↓' }}
              {{ deltaText(data.comparison.offersSentChangePercent!,
                           data.comparison.offersSentBefore) }}
              <span class="delta-note">vs previous 8 weeks</span>
            </span>
          }
          <span class="sub">
            @if (data.offersNoContact > 0) {
              {{ data.offersNoContact | number }} more are waiting on contact details.
            } @else {
              Every offer generated so far had somewhere to go.
            }
          </span>
        </div>
      </div>

      <div class="panels">
        <section class="card panel">
          <h2>Customer status breakdown</h2>
          <div class="donut-row">
            <!-- conic-gradient rather than a chart library: three static slices
                 don't justify the payload, and the same numbers are listed
                 beside it so the colour is never the only carrier. -->
            <div class="donut" [style.background]="donut(data)" role="img"
                 [attr.aria-label]="donutLabel(data)"></div>
            <ul class="legend">
              <li><span class="swatch came-back"></span>
                Came back <strong>{{ data.statusBreakdown.cameBack | number }}</strong></li>
              <li><span class="swatch quiet"></span>
                Still quiet <strong>{{ data.statusBreakdown.stillQuiet | number }}</strong></li>
              <li><span class="swatch new"></span>
                Not enough data <strong>{{ data.statusBreakdown.notEnoughData | number }}</strong></li>
            </ul>
          </div>
        </section>

        <section class="card panel">
          <h2>How far past their rhythm</h2>
          <p class="muted small" style="margin:4px 0 var(--space-4)">
            There's one reason a customer is flagged — they broke their own visit rhythm.
            This is how far past it they are, measured against their own threshold.
          </p>
          @if (data.overdueBuckets.length === 0) {
            <p class="muted small">Nobody is quiet right now.</p>
          } @else {
            <ul class="bars">
              @for (bucket of orderedBuckets(data); track bucket.bucket) {
                <li>
                  <span class="bar-label">{{ bucketLabel(bucket.bucket) }}</span>
                  <span class="bar-track">
                    <span class="bar-fill" [class]="bucket.bucket"
                          [style.width.%]="barWidth(bucket.customers, data)"></span>
                  </span>
                  <span class="bar-value">{{ bucket.customers | number }}</span>
                </li>
              }
            </ul>
          }
        </section>
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

    .delta {
      display: inline-flex;
      align-items: baseline;
      gap: var(--space-2);
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--success-600);
    }
    /* A fall in offers sent is not automatically bad — fewer people going quiet
       is the goal — so this is a neutral slate rather than an alarm red. */
    .delta.down { color: var(--text-muted); }
    .delta-note { font-weight: 400; color: var(--text-subtle); }
    .hero-delta { display: block; margin-top: var(--space-1); }

    .panels {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: var(--space-4);
      margin-bottom: var(--space-5);
    }
    .panel { padding: var(--space-5); }
    .panel h2 { font-size: var(--text-lg); margin: 0; }

    .donut-row {
      display: flex;
      align-items: center;
      gap: var(--space-5);
      margin-top: var(--space-4);
      flex-wrap: wrap;
    }
    .donut {
      flex: none;
      width: 132px;
      height: 132px;
      border-radius: 50%;
      /* Ring, not pie: the hole keeps the eye on relative arc length rather
         than inviting area comparison, which people read badly. */
      mask: radial-gradient(circle, transparent 58%, #000 59%);
      -webkit-mask: radial-gradient(circle, transparent 58%, #000 59%);
    }
    .legend { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
    .legend li {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--text-sm);
      color: var(--text-muted);
    }
    .legend strong { color: var(--text); font-variant-numeric: tabular-nums; }
    .swatch { width: 10px; height: 10px; border-radius: 3px; flex: none; }
    .swatch.came-back { background: var(--primary-500); }
    .swatch.quiet { background: #e3a008; }
    .swatch.new { background: var(--neutral-200); }

    .bars { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-3); }
    .bars li {
      display: grid;
      grid-template-columns: 150px minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--space-3);
      font-size: var(--text-sm);
    }
    .bar-label { color: var(--text-muted); }
    .bar-track { background: var(--surface-sunken); border-radius: var(--radius-full); height: 8px; }
    .bar-fill { display: block; height: 100%; border-radius: var(--radius-full); }
    /* Severity reads as a ramp, so the three bars are comparable at a glance. */
    .bar-fill.JUST_PAST { background: var(--primary-100); }
    .bar-fill.WELL_PAST { background: var(--primary-500); }
    .bar-fill.LONG_OVERDUE { background: var(--primary-700); }
    .bar-value { font-variant-numeric: tabular-nums; font-weight: 600; }

    @media (max-width: 560px) {
      .bars li { grid-template-columns: 1fr auto; }
      .bar-track { grid-column: 1 / -1; }
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

  /** Direction is carried by the arrow; the number itself reads unsigned. */
  abs(value: number): number {
    return Math.abs(value);
  }

  /**
   * Percentages need a base worth dividing by.
   *
   * Two offers becoming twenty-four is a true "+1,100%", and it reads as a
   * broken widget — the figure is dominated by how small the denominator was,
   * not by what changed. Below a handful of events the absolute move is both
   * more honest and easier to read, so this switches to "from 2" and lets the
   * headline number carry the rest.
   */
  deltaText(changePercent: number, before: number): string {
    const MEANINGFUL_BASE = 5;
    if (before < MEANINGFUL_BASE) {
      return `from ${before}`;
    }
    return `${Math.round(Math.abs(changePercent))}%`;
  }

  /**
   * Slices in the same order as the legend. Percentages are derived here rather
   * than sent by the API because they are pure presentation — the counts are
   * the fact, and three counts that must sum to the total are better checked in
   * one place than trusted from two.
   */
  donut(data: OverviewStats): string {
    const { cameBack, stillQuiet, notEnoughData } = data.statusBreakdown;
    const total = cameBack + stillQuiet + notEnoughData;
    if (total === 0) {
      return 'var(--neutral-200)';
    }
    const first = (cameBack / total) * 100;
    const second = first + (stillQuiet / total) * 100;
    return `conic-gradient(var(--primary-500) 0 ${first}%, `
      + `#e3a008 ${first}% ${second}%, var(--neutral-200) ${second}% 100%)`;
  }

  donutLabel(data: OverviewStats): string {
    const { cameBack, stillQuiet, notEnoughData } = data.statusBreakdown;
    return `${cameBack} came back, ${stillQuiet} still quiet, `
      + `${notEnoughData} without enough data to judge.`;
  }

  /** Severity order, so the list reads worst-last regardless of row order. */
  orderedBuckets(data: OverviewStats) {
    const order = ['JUST_PAST', 'WELL_PAST', 'LONG_OVERDUE'];
    return [...data.overdueBuckets].sort(
      (a, b) => order.indexOf(a.bucket) - order.indexOf(b.bucket));
  }

  bucketLabel(bucket: string): string {
    switch (bucket) {
      case 'JUST_PAST': return 'Just past their gap';
      case 'WELL_PAST': return 'Well past it';
      default: return 'Long overdue';
    }
  }

  /**
   * Scaled against the largest bucket, not the total: with one dominant bucket
   * every other bar would round to a sliver and stop being comparable.
   */
  barWidth(customers: number, data: OverviewStats): number {
    const largest = Math.max(...data.overdueBuckets.map((b) => b.customers), 1);
    return Math.max((customers / largest) * 100, 4);
  }
}
