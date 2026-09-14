import { DecimalPipe, LowerCasePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { OverviewStats, ReportingWindowKey, RetentionImpact } from '../core/api.models';
import { ActivityChartComponent } from '../shared/activity-chart.component';

/** The reporting periods on offer, in the order they are listed. */
const WINDOWS: readonly { key: ReportingWindowKey; label: string }[] = [
  { key: 'LAST_7_DAYS', label: 'Last 7 days' },
  { key: 'LAST_30_DAYS', label: 'Last 30 days' },
  { key: 'LAST_8_WEEKS', label: 'Last 8 weeks' },
];

/**
 * The landing screen after login (FR-7).
 *
 * <p>Every number here is computed server-side by `/v1/stats/overview` — one
 * request, one pass over the tables. Counting a paginated flag list on the
 * client would either count a single page or scan every page to produce an
 * integer.
 *
 * <p>The screen holds two kinds of figure and is at pains not to blur them. What
 * happened over a period — flags raised, customers recovered, offers sent, the
 * recovery rate — follows the reporting period selector. How things stand right
 * now — who is drifting, who is worth calling — is derived from every customer's
 * whole history, is the same on every setting, and carries no period label. The
 * note under the selector says so, because an operator who thinks the selector
 * changed who is drifting will stop trusting the flag list.
 */
@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [RouterLink, DecimalPipe, LowerCasePipe, FormsModule, ActivityChartComponent],
  template: `
    <div class="head">
      <div>
        <h1>Overview</h1>
        <p class="muted small" style="margin:4px 0 0">
          How many of your regulars are drifting, and how many came back after an offer.
        </p>
      </div>
      <div class="head-actions">
        <label class="period" for="period">
          <span>Reporting period</span>
          <select id="period" name="period"
                  [ngModel]="window()" (ngModelChange)="onWindowChosen($event)"
                  [disabled]="loading()">
            @for (option of windows; track option.key) {
              <option [value]="option.key">{{ option.label }}</option>
            }
          </select>
        </label>
        <a routerLink="/flags" class="btn-secondary link-btn">See flagged customers</a>
      </div>
    </div>

    <!-- The one thing a period selector can be mistaken for. Says plainly which
         figures it moves and which it cannot, at the point of the control. -->
    <p class="scope muted small">
      This period applies to the activity figures below — offers, recoveries, the
      chart. Who is drifting is always worked out from each customer's whole visit
      history, so changing it never changes who is on your list.
    </p>

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
    } @else if (error() && !stats()) {
      <div class="banner banner-error" role="alert">
        <span>{{ error() }}</span>
        <button type="button" class="btn-secondary retry" (click)="load()">Try again</button>
      </div>
    } @else {
      <!-- Nested, not an else-if with an alias: Angular allows the "as" alias
           on a plain if only. -->
      @if (stats(); as data) {
      <!-- A window that failed to load leaves the last one on screen rather
           than emptying the dashboard: the numbers are stale, not wrong, and
           saying which is more useful than saying nothing. -->
      @if (error()) {
        <div class="banner banner-warning" role="status">
          <span>{{ error() }} The figures below are from the last period that loaded.</span>
          <button type="button" class="btn-secondary retry" (click)="load()">Try again</button>
        </div>
      }
      <!-- The headline gets its own full-width row: the only number here that
           says whether any of this is working, and the one that needs room to
           carry its denominator. Sharing a row with the others left a hole in
           the grid, since a 2-wide card doesn't divide into the remaining three. -->
      <div class="hero">
        <div class="card stat stat-primary">
          <span class="label">Came back after an offer</span>
          @if (data.recovery.percent === null) {
            <span class="value">—</span>
            <span class="sub">No offer reached a customer in
              {{ data.reportingPeriod.label | lowercase }}, so there's nothing to measure.</span>
          } @else {
            <span class="value">{{ data.recovery.percent | number: '1.0-1' }}%</span>
            <span class="sub">
              {{ data.recovery.returned | number }} of
              {{ data.recovery.contacted | number }}
              {{ data.recovery.contacted === 1 ? 'customer' : 'customers' }} who received an offer
              in {{ data.reportingPeriod.label | lowercase }}
              bought again within {{ data.recovery.windowDays }} days.
              <!-- Only claimed when the earlier period had enough offers to have
                   had a rate at all. A jump "from 0%" over two offers is a fact
                   about the sample size, not about retention. -->
              @if (data.comparison.recoveryPercentChange !== null
                    && data.comparison.offersSentBefore >= 5) {
                <span class="hero-delta">
                  {{ data.comparison.recoveryPercentChange! > 0 ? '↑' : '↓' }}
                  {{ abs(data.comparison.recoveryPercentChange!) | number: '1.0-1' }}
                  points vs {{ previousPeriod(data) }}.
                </span>
              }
            </span>
          }
          <details class="def def-on-primary">
            <summary>What this counts</summary>
            <p>{{ data.definitions.recovery }}</p>
            @if (data.recovery.neverContacted > 0) {
              <p>
                {{ data.recovery.neverContacted | number }} other
                {{ data.recovery.neverContacted === 1 ? 'flag' : 'flags' }} never reached anyone —
                no contact details, a delivery failure, or the offer budget already spent. They
                aren't counted as misses above. That number is the opportunity you couldn't attempt.
              </p>
            }
          </details>
        </div>
      </div>

      <div class="cards">
        <div class="card stat">
          <span class="label">Customers monitored</span>
          <span class="value">{{ data.customersMonitored | number }}</span>
          <span class="sub">
            of {{ data.customersTotal | number }} —
            {{ data.customersBelowThreshold | number }} seen too few times to have a rhythm yet.
          </span>
          <details class="def">
            <summary>What this counts</summary>
            <p>{{ data.definitions.customersMonitored }}</p>
          </details>
        </div>

        <div class="card stat">
          <span class="label">Currently drifting</span>
          <span class="value">{{ data.statusBreakdown.drifting | number }}</span>
          <span class="sub">Outside their normal rhythm and not back yet.</span>
        </div>

        <div class="card stat">
          <span class="label">High-value customers drifting</span>
          <span class="value">{{ data.highValueDrifting | number }}</span>
          <span class="sub">
            @if (data.highValueDrifting === 0) {
              Nobody worth well above your average is drifting right now.
            } @else {
              Worth well above your average customer, and overdue. Worth a call today.
            }
          </span>
          <details class="def">
            <summary>What this counts</summary>
            <p>{{ data.definitions.highValueDrifting }}</p>
          </details>
        </div>

        <div class="card stat">
          <span class="label">Offers sent</span>
          <span class="value">{{ data.offersSent | number }}</span>
          <!-- The only stat card with a trend line. "Monitored" and "currently
               drifting" are counts of how things stand right now; nothing
               records how they stood a period ago, so an arrow beside them
               would be reconstructed rather than measured. -->
          @if (data.comparison.offersSentChangePercent !== null) {
            <span class="delta" [class.down]="data.comparison.offersSentChangePercent! < 0">
              {{ data.comparison.offersSentChangePercent! > 0 ? '↑' : '↓' }}
              {{ deltaText(data.comparison.offersSentChangePercent!,
                           data.comparison.offersSentBefore) }}
              <span class="delta-note">vs {{ previousPeriod(data) }}</span>
            </span>
          }
          <span class="sub">
            <!-- Deliberately not "N more": this is a backlog right now, not part
                 of the period count above, and "more" would invite the reader to
                 add two numbers that measure different things. -->
            @if (data.offersNoContact > 0) {
              Separately, {{ data.offersNoContact | number }} are waiting on contact details now.
            } @else {
              Nothing is stuck for want of contact details.
            }
          </span>
        </div>
      </div>

      <!-- Money, when the imported data supports it. Its own request, so this
           section failing leaves every count above intact. -->
      @if (impactError()) {
        <div class="banner banner-warning" role="status">
          <span>Couldn't load the revenue figures. The counts above are unaffected.</span>
        </div>
      } @else {
        <!-- Nested rather than an else-if, for the same reason the stats alias
             above is: the "as" alias attaches to a plain if only. -->
        @if (impact(); as money) {
          <section class="card impact">
            <div class="impact-head">
              <h2>Retention impact</h2>
              <span class="impact-scope">
                <!-- Stated because the selector above does not reach here. A
                     revenue figure that silently changed with a reporting window
                     would read as the programme's worth having moved. -->
                <span class="chip">All time</span>
                <span class="muted small">Every offer ever delivered, in {{ money.currencyCode }}</span>
              </span>
            </div>
            <p class="muted small" style="margin:0 0 var(--space-4)">
              What the programme has been worth so far. The reporting period above doesn't
              apply here — this is the running total since your first offer.
            </p>

            <div class="impact-grid">
              <div class="impact-figure">
                <span class="label">Revenue from customers who returned</span>
                <span class="value money">{{ money.revenueFromReturnedCustomers | number: '1.2-2' }}
                  <span class="code">{{ money.currencyCode }}</span></span>
                <span class="sub">{{ money.returned | number }} customers, within
                  {{ money.recoveryWindowDays }} days of their offer.</span>
              </div>

              <div class="impact-figure">
                <span class="label">Intervention cost</span>
                @if (money.interventionCost === null) {
                  <span class="value">—</span>
                  <span class="sub">
                    {{ money.freeItemOffers | number }}
                    free-{{ money.freeItemOffers === 1 ? 'item' : 'items' }} offer{{
                      money.freeItemOffers === 1 ? '' : 's' }} can't be valued: there's no item price
                    in your transaction data. No total is shown rather than a partial one.
                  </span>
                } @else {
                  <span class="value money">{{ money.interventionCost | number: '1.2-2' }}
                    <span class="code">{{ money.currencyCode }}</span></span>
                  <span class="sub">Across {{ money.contacted | number }} delivered
                    {{ money.contacted === 1 ? 'offer' : 'offers' }}.</span>
                }
              </div>

              <div class="impact-figure">
                <span class="label">Net value</span>
                @if (money.netValue === null) {
                  <span class="value">—</span>
                  <span class="sub">Not shown while any offer's cost is unknown.</span>
                } @else {
                  <span class="value money" [class.negative]="money.netValue < 0">
                    {{ money.netValue | number: '1.2-2' }}
                    <span class="code">{{ money.currencyCode }}</span></span>
                  <span class="sub">Revenue from returned customers, less the recorded cost.</span>
                }
              </div>

              <div class="impact-figure">
                <span class="label">Average recovered customer</span>
                @if (money.averageRecoveredCustomerValue === null) {
                  <span class="value">—</span>
                  <span class="sub">Nobody has returned yet.</span>
                } @else {
                  <span class="value money">{{ money.averageRecoveredCustomerValue | number: '1.2-2' }}
                    <span class="code">{{ money.currencyCode }}</span></span>
                  <span class="sub">What one returning customer spends in their window.</span>
                }
              </div>
            </div>

            <!-- The methodology is part of the output, not a footnote. These
                 figures are what a buyer acts on; without their definitions they
                 are just numbers with a currency sign. -->
            <details class="def def-wide">
              <summary>How these are worked out</summary>
              <dl class="method">
                <dt>Revenue from customers who returned</dt>
                <dd>{{ money.definitions.revenueFromReturnedCustomers }}</dd>
                <dt>Intervention cost</dt>
                <dd>{{ money.definitions.interventionCost }}</dd>
                <dt>Net value</dt>
                <dd>{{ money.definitions.netValue }}</dd>
                <dt>Cohort</dt>
                <dd>{{ money.definitions.contacted }}</dd>
              </dl>
            </details>
          </section>
        }
      }

      <div class="panels">
        <section class="card panel">
          <h2>Customer status breakdown</h2>
          <div class="donut-row">
            <!-- conic-gradient rather than a chart library: four static slices
                 don't justify the payload, and the same numbers are listed
                 beside it so the colour is never the only carrier. -->
            <div class="donut" [style.background]="donut(data)" role="img"
                 [attr.aria-label]="donutLabel(data)"></div>
            <ul class="legend">
              <li><span class="swatch drifting"></span>
                Drifting now <strong>{{ data.statusBreakdown.drifting | number }}</strong></li>
              <li><span class="swatch came-back"></span>
                Returned <strong>{{ data.statusBreakdown.returned | number }}</strong></li>
              <li><span class="swatch on-rhythm"></span>
                On rhythm <strong>{{ data.statusBreakdown.onRhythm | number }}</strong></li>
              <li><span class="swatch new"></span>
                Not enough data <strong>{{ data.statusBreakdown.notEnoughData | number }}</strong></li>
              <li class="legend-total">
                All customers <strong>{{ data.statusBreakdown.total | number }}</strong></li>
            </ul>
          </div>
          <details class="def">
            <summary>What this counts</summary>
            <p>{{ data.definitions.statusBreakdown }}</p>
          </details>
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
        <h2>{{ data.reportingPeriod.label }}</h2>
        <p class="muted small" style="margin:4px 0 var(--space-4)">
          Flags raised each {{ data.reportingPeriod.unit }}, against customers who came back
          after being flagged.
        </p>
        <app-activity-chart [points]="data.activitySeries" [unit]="data.reportingPeriod.unit" />
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
    .head-actions {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      flex-wrap: wrap;
    }
    .period {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--text-sm);
      color: var(--text-muted);
      white-space: nowrap;
    }
    .period select {
      min-height: var(--control-height);
      padding: 0 var(--space-2);
      font: inherit;
      font-weight: 500;
      color: var(--text);
      background: var(--surface);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius-sm);
    }
    /* Sits directly under the control it explains, so the scoping is read at the
       moment the selector is used rather than discovered later. */
    .scope {
      max-width: 72ch;
      margin: calc(var(--space-2) * -1) 0 var(--space-4);
    }
    .impact-scope { display: inline-flex; align-items: center; gap: var(--space-2); }
    .chip {
      padding: 2px var(--space-2);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius-full);
      font-size: var(--text-xs);
      font-weight: 600;
      color: var(--text-muted);
      background: var(--surface-sunken);
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
    /* The only figure here that carries a decimal, and the data face is
       monospaced — the point gets a full digit's advance width, so "41.4%"
       reads as "41 . 4%". The UI face sets it as one number. The monospaced
       face stays on the whole-number stats, where it costs nothing. */
    .stat-primary .value {
      font-size: 2.75rem;
      font-family: var(--font-ui);
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.03em;
    }
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
    /* Swatch colours are the donut segment colours, in the donut's order. */
    .swatch.drifting { background: #e3a008; }
    .swatch.came-back { background: var(--primary-500); }
    .swatch.on-rhythm { background: var(--success-600); }
    .swatch.new { background: var(--neutral-200); }
    /* The partition's total, ruled off from its four parts so it reads as the
       sum rather than as a fifth category. */
    .legend-total {
      border-top: 1px solid var(--border);
      padding-top: var(--space-2);
      margin-top: var(--space-1);
      color: var(--text);
    }

    /* "What this counts" — the definition of a figure, disclosed beside it
       rather than hidden in a tooltip or a help page. Closed by default so the
       numbers stay the loudest thing on the card. */
    .def { margin-top: auto; padding-top: var(--space-3); }
    .def summary {
      cursor: pointer;
      font-size: var(--text-xs);
      color: var(--text-subtle);
      list-style: none;
      text-decoration: underline dotted;
      text-underline-offset: 2px;
    }
    .def summary::-webkit-details-marker { display: none; }
    .def summary:hover { color: var(--text-muted); }
    .def p {
      margin: var(--space-2) 0 0;
      font-size: var(--text-xs);
      line-height: 1.5;
      color: var(--text-muted);
    }
    .def-on-primary { grid-column: 1 / -1; }
    .def-on-primary summary,
    .def-on-primary p { color: rgba(255, 255, 255, 0.82); }
    .def-on-primary summary:hover { color: #fff; }
    .def-wide { margin-top: var(--space-4); }

    .impact { padding: var(--space-5); margin-bottom: var(--space-5); }
    .impact-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
    }
    .impact-head h2 { font-size: var(--text-lg); margin: 0; }
    .impact-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: var(--space-5);
    }
    .impact-figure { display: flex; flex-direction: column; gap: var(--space-1); }
    .impact-figure .value { font-size: 1.5rem; }
    /* No thousands separators invented and no rounding to whole units: the
       amount is whatever the POS export said it was. */
    .code { font-size: var(--text-sm); font-weight: 500; color: var(--text-muted); }
    .value.negative { color: var(--danger-600); }
    .method { margin: var(--space-2) 0 0; font-size: var(--text-xs); }
    .method dt { font-weight: 600; color: var(--text); margin-top: var(--space-2); }
    .method dd { margin: 2px 0 0; color: var(--text-muted); line-height: 1.5; }

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

  readonly windows = WINDOWS;

  /**
   * The reporting period. Held here, sent on each request, and stored nowhere
   * server-side: it is a way of reading the data, not a setting of the account,
   * so it cannot outlive the screen or change what the model does.
   */
  readonly window = signal<ReportingWindowKey>('LAST_8_WEEKS');

  readonly stats = signal<OverviewStats | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /**
   * The money section loads separately. It is the only part of this screen that
   * needs transaction amounts, so a business whose import carried no amounts
   * still gets every count above rather than an empty page.
   */
  readonly impact = signal<RetentionImpact | null>(null);
  readonly impactError = signal(false);

  /** Guards against a slow earlier window landing after a faster later one. */
  private request = 0;

  constructor() {
    this.load();
    this.loadImpact();
  }

  onWindowChosen(key: ReportingWindowKey): void {
    if (key === this.window()) {
      return;
    }
    this.window.set(key);
    this.load();
  }

  load(): void {
    const token = ++this.request;
    // Skeletons only for the first load. A period change has numbers to show
    // already, and blanking the page to replace them reads as a failure.
    this.loading.set(this.stats() === null);
    this.error.set(null);

    this.api.getOverview(this.window()).subscribe({
      next: (data) => {
        if (token !== this.request) {
          return;
        }
        this.stats.set(data);
        this.loading.set(false);
      },
      error: () => {
        if (token !== this.request) {
          return;
        }
        this.loading.set(false);
        this.error.set('Could not load your figures.');
      },
    });
  }

  loadImpact(): void {
    this.impactError.set(false);
    this.api.getRetentionImpact().subscribe({
      next: (data) => this.impact.set(data),
      error: () => {
        this.impact.set(null);
        this.impactError.set(true);
      },
    });
  }

  /**
   * How the comparison's other half is described in prose. Derived from the
   * period rather than written into the template, because "vs previous 8 weeks"
   * is a claim about what was measured and would otherwise survive a change to
   * the selector as a lie.
   */
  previousPeriod(data: OverviewStats): string {
    return `the previous ${data.reportingPeriod.label.replace('Last ', '')}`;
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
   * the fact, and four counts that must sum to the total are better checked in
   * one place than trusted from two.
   *
   * The four buckets are a partition of the customer base, so the arcs always
   * close on 100% and the ring stands for the whole book of customers. That is
   * the property the screen used to lack: the old breakdown mixed flag counts
   * with customer counts and did not add up to anything.
   */
  donut(data: OverviewStats): string {
    const { drifting, returned, onRhythm, notEnoughData } = data.statusBreakdown;
    const total = drifting + returned + onRhythm + notEnoughData;
    if (total === 0) {
      return 'var(--neutral-200)';
    }
    const driftingEnd = (drifting / total) * 100;
    const returnedEnd = driftingEnd + (returned / total) * 100;
    const onRhythmEnd = returnedEnd + (onRhythm / total) * 100;
    return `conic-gradient(#e3a008 0 ${driftingEnd}%, `
      + `var(--primary-500) ${driftingEnd}% ${returnedEnd}%, `
      + `var(--success-600) ${returnedEnd}% ${onRhythmEnd}%, `
      + `var(--neutral-200) ${onRhythmEnd}% 100%)`;
  }

  donutLabel(data: OverviewStats): string {
    const { drifting, returned, onRhythm, notEnoughData, total } = data.statusBreakdown;
    return `Of ${total} customers: ${drifting} drifting now, ${returned} returned, `
      + `${onRhythm} on rhythm, ${notEnoughData} without enough data to judge.`;
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
