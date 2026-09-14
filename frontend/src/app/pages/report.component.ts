import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { OverdueBand, RetentionDiagnostic } from '../core/api.models';

/**
 * The retention diagnostic (§7) as one page.
 *
 * Not a dashboard and deliberately not shaped like one: the overview leads with
 * what is urgent this morning, and this leads with the shape of the whole
 * customer base, because the question it answers is whether retention is getting
 * better or worse. It is also the thing an operator would print or hand to
 * someone, which is why the ordering is fixed and the method behind the one
 * projected figure sits under it rather than behind a tooltip.
 *
 * Every figure is a count of something observed except the opportunity, which
 * says on its face what it is made of and is withheld outright when the cohort
 * behind it is too thin to carry it.
 */
@Component({
  selector: 'app-report',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe],
  template: `
    @if (report(); as data) {
      <header class="head">
        <div>
          <h1>Retention diagnostic</h1>
          <p class="muted small" style="margin:4px 0 0">
            Generated {{ data.generatedAt | date: 'd MMM yyyy, HH:mm' }}. Customer behaviour and
            interventions cover this business's full history, so this report reads the same
            regardless of the period chosen on the overview.
          </p>
        </div>
        <button type="button" class="btn-secondary no-print" (click)="print()">
          Print or save as PDF
        </button>
      </header>

      <!-- 1. Executive summary -------------------------------------------- -->
      <section class="section">
        <h2>Executive summary</h2>
        <div class="figures">
          <div class="figure">
            <span class="value">{{ data.executiveSummary.customersAnalyzed | number }}</span>
            <span class="label">Customers analysed</span>
            <p class="def">{{ data.definitions.customersAnalyzed }}</p>
          </div>
          <div class="figure">
            <span class="value">
              {{ data.executiveSummary.customersWithSufficientHistory | number }}
            </span>
            <span class="label">With enough history to judge</span>
            <p class="def">{{ data.definitions.customersWithSufficientHistory }}</p>
          </div>
          <div class="figure">
            <span class="value accent">
              {{ data.executiveSummary.customersShowingCadenceDisruption | number }}
            </span>
            <span class="label">Showing cadence disruption</span>
            <p class="def">{{ data.definitions.customersShowingCadenceDisruption }}</p>
          </div>
          <div class="figure">
            <span class="value">
              {{ data.executiveSummary.highValueCustomersAtRisk | number }}
            </span>
            <span class="label">High-value customers at risk</span>
            <p class="def">{{ data.definitions.highValueCustomersAtRisk }}</p>
          </div>
          <div class="figure">
            <span class="value">
              {{ data.executiveSummary.customersReturnedAfterIntervention | number }}
            </span>
            <span class="label">Returned after an intervention</span>
            <p class="def">{{ data.definitions.customersReturnedAfterIntervention }}</p>
          </div>
          <div class="figure">
            @if (data.executiveSummary.estimatedRetentionOpportunity !== null) {
              <span class="value money">
                {{ data.interventionPerformance.currencyCode }}
                {{ data.executiveSummary.estimatedRetentionOpportunity | number: '1.2-2' }}
              </span>
              <span class="label">Estimated retention opportunity</span>
              <p class="def">
                See <a href="#opportunity">the opportunity section</a> for what this is made of.
              </p>
            } @else {
              <span class="value muted">Not estimated</span>
              <span class="label">Retention opportunity</span>
              <p class="def">{{ data.opportunity.unavailableReason }}</p>
            }
          </div>
        </div>
      </section>

      <!-- 2. Customer behaviour ------------------------------------------- -->
      <section class="section">
        <h2>Customer behaviour</h2>

        @if (data.customerBehaviour.typicalPurchaseIntervals; as spread) {
          <div class="card block">
            <h3>Typical purchase intervals</h3>
            <div class="quartiles">
              <div>
                <span class="value">{{ spread.p25Days | number: '1.0-1' }}</span>
                <span class="label">days — a quarter visit more often</span>
              </div>
              <div>
                <span class="value accent">{{ spread.medianDays | number: '1.0-1' }}</span>
                <span class="label">days — the typical customer</span>
              </div>
              <div>
                <span class="value">{{ spread.p75Days | number: '1.0-1' }}</span>
                <span class="label">days — a quarter visit less often</span>
              </div>
              <div>
                <span class="value">{{ spread.meanDays | number: '1.0-1' }}</span>
                <span class="label">days — the average, for comparison</span>
              </div>
            </div>
            <p class="def">{{ data.definitions.typicalPurchaseIntervals }}</p>
          </div>
        } @else {
          <div class="card block">
            <h3>Typical purchase intervals</h3>
            <p class="muted small" style="margin:var(--space-2) 0 0">
              No customer has enough history for a rhythm to be measured yet.
            </p>
            <p class="def">{{ data.definitions.typicalPurchaseIntervals }}</p>
          </div>
        }

        <div class="grid-2">
          <div class="card block">
            <h3>Cadence segments</h3>
            <p class="def" style="margin-top:var(--space-1)">
              {{ data.definitions.cadenceSegments }}
            </p>
            <ul class="distribution">
              @for (segment of data.customerBehaviour.cadenceSegments; track segment.key) {
                <li>
                  <div class="row-head">
                    <span class="row-label">{{ segment.label }}</span>
                    <span class="row-value">{{ segment.customers | number }}</span>
                  </div>
                  <div class="bar" aria-hidden="true">
                    <span [style.width.%]="barWidth(segment.sharePercent)"></span>
                  </div>
                  <span class="row-meta">
                    {{ segment.intervalRange }} · {{ segment.visitsPerMonth }} ·
                    {{ shareText(segment.sharePercent) }}
                  </span>
                </li>
              }
            </ul>
          </div>

          <div class="card block">
            <h3>Purchases on record</h3>
            <p class="def" style="margin-top:var(--space-1)">
              {{ data.definitions.purchaseCountDistribution }}
            </p>
            <ul class="distribution">
              @for (band of data.customerBehaviour.purchaseCountDistribution; track band.key) {
                <li>
                  <div class="row-head">
                    <span class="row-label">{{ band.label }}</span>
                    <span class="row-value">{{ band.customers | number }}</span>
                  </div>
                  <div class="bar" aria-hidden="true">
                    <span [style.width.%]="barWidth(band.sharePercent)"></span>
                  </div>
                  <span class="row-meta">{{ shareText(band.sharePercent) }}</span>
                </li>
              }
            </ul>
          </div>
        </div>
      </section>

      <!-- 3. At-risk customers -------------------------------------------- -->
      <section class="section">
        <h2>At-risk customers</h2>
        <p class="muted small" style="margin:0 0 var(--space-3)">
          The most urgent {{ data.atRiskCustomers.length }} of
          {{ data.atRiskTotal | number }} customers currently past their own threshold, in the
          same priority order as the
          <a routerLink="/flags">flagged customers</a> list.
        </p>

        @if (data.atRiskCustomers.length === 0) {
          <div class="card block">
            <p style="margin:0">Nobody is drifting right now.</p>
          </div>
        } @else {
          <div class="card table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col">Normal cadence</th>
                  <th scope="col">Current gap</th>
                  <th scope="col" class="num">Customer value</th>
                  <th scope="col">Priority</th>
                  <th scope="col">Recommended next action</th>
                </tr>
              </thead>
              <tbody>
                @for (flag of data.atRiskCustomers; track flag.flagId) {
                  <tr>
                    <td>
                      <a [routerLink]="['/flags', flag.flagId]">
                        {{ flag.customerName || flag.customerRef }}
                      </a>
                      @if (flag.customerName) {
                        <span class="mono meta">{{ flag.customerRef }}</span>
                      }
                    </td>
                    <td>every {{ flag.avgIntervalDaysAtFlag | number: '1.0-1' }} days</td>
                    <td>
                      @if (flag.daysOverdue !== null) {
                        {{ flag.daysOverdue | number: '1.0-0' }} days past their threshold
                      } @else {
                        <span class="muted">—</span>
                      }
                    </td>
                    <td class="num">
                      {{ data.interventionPerformance.currencyCode }}
                      {{ flag.totalSpend | number: '1.2-2' }}
                    </td>
                    <td>
                      @if (flag.riskBand; as band) {
                        <span [class]="'pill ' + riskClass(band)">{{ riskLabel(band) }}</span>
                        <span class="mono meta">{{ flag.priorityScore }}/100</span>
                      } @else {
                        <span class="muted">—</span>
                      }
                    </td>
                    <td>{{ flag.recommendedAction }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <!-- 4. Intervention performance ------------------------------------- -->
      <section class="section">
        <h2>Intervention performance</h2>
        <div class="figures">
          <div class="figure">
            <span class="value">
              {{ data.interventionPerformance.impact.contacted | number }}
            </span>
            <span class="label">Offers delivered</span>
          </div>
          <div class="figure">
            <span class="value accent">
              {{ data.interventionPerformance.impact.returned | number }}
            </span>
            <span class="label">Customers returned</span>
          </div>
          <div class="figure">
            <span class="value">
              @if (data.interventionPerformance.impact.recoveryPercent !== null) {
                {{ data.interventionPerformance.impact.recoveryPercent | number: '1.0-1' }}%
              } @else {
                <span class="muted">No rate yet</span>
              }
            </span>
            <span class="label">
              Return rate, within
              {{ data.interventionPerformance.impact.recoveryWindowDays }} days
            </span>
          </div>
          <div class="figure">
            <span class="value money">
              {{ data.interventionPerformance.currencyCode }}
              {{ data.interventionPerformance.impact.revenueFromReturnedCustomers | number: '1.2-2' }}
            </span>
            <span class="label">Spent by returning customers</span>
          </div>
          <div class="figure">
            <span class="value money">
              @if (data.interventionPerformance.impact.interventionCost !== null) {
                {{ data.interventionPerformance.currencyCode }}
                {{ data.interventionPerformance.impact.interventionCost | number: '1.2-2' }}
              } @else {
                <span class="muted">Unknown</span>
              }
            </span>
            <span class="label">Intervention cost</span>
          </div>
          <div class="figure">
            <span class="value">
              {{ data.interventionPerformance.flaggedNeverContacted | number }}
            </span>
            <span class="label">Flagged, but no offer ever reached them</span>
          </div>
        </div>

        <div class="card block">
          <p style="margin:0">{{ data.interventionPerformance.impact.definitions.returned }}</p>
          <p style="margin:var(--space-2) 0 0">
            {{ data.interventionPerformance.impact.definitions.revenueFromReturnedCustomers }}
          </p>
          <p style="margin:var(--space-2) 0 0">
            {{ data.interventionPerformance.impact.definitions.netValue }}
          </p>
        </div>
      </section>

      <!-- 5. Opportunity --------------------------------------------------- -->
      <section class="section" id="opportunity">
        <h2>Opportunity</h2>

        @if (data.opportunity.available) {
          <div class="card block">
            <div class="headline">
              <span class="value money big">
                {{ data.interventionPerformance.currencyCode }}
                {{ data.opportunity.estimatedRevenue | number: '1.2-2' }}
              </span>
              <span class="label">
                Estimated opportunity from the {{ data.opportunity.customersInScope | number }}
                customers drifting today
              </span>
            </div>

            <h3 style="margin-top:var(--space-5)">How this is calculated</h3>
            <div class="table-scroll">
            <table class="working">
              <tbody>
                <tr>
                  <th scope="row">Customers drifting today</th>
                  <td class="num">{{ data.opportunity.customersInScope | number }}</td>
                </tr>
                <tr>
                  <th scope="row">Offers delivered, the rate is measured over</th>
                  <td class="num">{{ data.opportunity.cohortContacted | number }}</td>
                </tr>
                <tr>
                  <th scope="row">Returned after an offer</th>
                  <td class="num">
                    {{ data.opportunity.historicalRecoveryPercent | number: '1.0-1' }}%
                  </td>
                </tr>
                <tr>
                  <th scope="row">Expected returns</th>
                  <td class="num">{{ data.opportunity.expectedReturns | number }}</td>
                </tr>
                <tr>
                  <th scope="row">Spent by a returning customer</th>
                  <td class="num">
                    {{ data.interventionPerformance.currencyCode }}
                    {{ data.opportunity.averageReturningCustomerValue | number: '1.2-2' }}
                  </td>
                </tr>
                <tr class="total">
                  <th scope="row">Estimated revenue from those returns</th>
                  <td class="num">
                    {{ data.interventionPerformance.currencyCode }}
                    {{ data.opportunity.estimatedRevenue | number: '1.2-2' }}
                  </td>
                </tr>
                @if (data.opportunity.estimatedCost !== null) {
                  <tr>
                    <th scope="row">
                      Cost of offers to all {{ data.opportunity.customersInScope | number }}
                    </th>
                    <td class="num">
                      {{ data.interventionPerformance.currencyCode }}
                      {{ data.opportunity.estimatedCost | number: '1.2-2' }}
                    </td>
                  </tr>
                  <tr class="total">
                    <th scope="row">Estimated net</th>
                    <td class="num">
                      {{ data.interventionPerformance.currencyCode }}
                      {{ data.opportunity.estimatedNet | number: '1.2-2' }}
                    </td>
                  </tr>
                } @else {
                  <tr>
                    <th scope="row">Cost of offers</th>
                    <td class="num muted">Unknown — a free item has no price in the data</td>
                  </tr>
                }
              </tbody>
            </table>
            </div>

            <p class="method">{{ data.opportunity.method }}</p>
            <p class="def">{{ data.definitions.opportunity }}</p>
          </div>
        } @else {
          <div class="card block">
            <p style="margin:0"><strong>No estimate is shown.</strong></p>
            <p style="margin:var(--space-2) 0 0">
              {{ data.opportunity.unavailableReason }}
            </p>
            <p class="method">{{ data.opportunity.method }}</p>
            <p class="def">{{ data.definitions.opportunity }}</p>
          </div>
        }
      </section>
    } @else if (error()) {
      <div class="banner banner-error" role="alert">
        <span>{{ error() }}</span>
      </div>
    } @else {
      <div class="card" style="padding:var(--space-5)">
        @for (row of [1, 2, 3, 4]; track row) {
          <div class="skeleton" style="margin:14px 0"></div>
        }
      </div>
    }
  `,
  styles: [`
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-4);
      margin-bottom: var(--space-6);
    }
    .section { margin-bottom: var(--space-8); }
    .section h2 { margin-bottom: var(--space-3); }
    .block { padding: var(--space-5); }

    /* A grid rather than a row of cards: these are one set of figures about one
       business, and boxing each separately would suggest otherwise. */
    .figures {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: var(--space-3);
    }
    .figure {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: var(--space-4);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
    }
    .value {
      font-size: 1.5rem;
      font-weight: 600;
      line-height: 1.2;
      font-variant-numeric: tabular-nums;
    }
    .value.big { font-size: 2rem; }
    .value.accent { color: var(--primary-600); }
    .value.money { font-family: var(--font-data); font-size: 1.25rem; }
    .label { font-size: var(--text-sm); color: var(--text-muted); }

    /* The definition sits with its figure rather than in a glossary, because a
       number a reader cannot define is one they will not act on. */
    .def {
      margin: var(--space-2) 0 0;
      font-size: var(--text-xs);
      line-height: 1.5;
      color: var(--text-subtle);
    }

    .quartiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--space-4);
      margin-top: var(--space-3);
    }
    .quartiles > div { display: flex; flex-direction: column; gap: 2px; }
    .quartiles .value { font-size: 1.25rem; }

    .grid-2 {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: var(--space-3);
      margin-top: var(--space-3);
    }

    .distribution { list-style: none; margin: var(--space-3) 0 0; padding: 0; }
    .distribution li + li { margin-top: var(--space-3); }
    .row-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .row-label { font-size: var(--text-sm); font-weight: 500; }
    .row-value { font-variant-numeric: tabular-nums; font-weight: 600; }
    .row-meta { font-size: var(--text-xs); color: var(--text-subtle); }
    .bar {
      height: 6px;
      margin: 6px 0 4px;
      border-radius: var(--radius-full);
      background: var(--surface-sunken);
      overflow: hidden;
    }
    .bar span {
      display: block;
      height: 100%;
      border-radius: var(--radius-full);
      background: var(--primary-500);
    }

    table { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
    th, td {
      padding: 10px var(--space-4);
      text-align: left;
      border-bottom: 1px solid var(--border);
      vertical-align: top;
    }
    thead th {
      font-size: var(--text-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-subtle);
      white-space: nowrap;
    }
    tbody tr:last-child td { border-bottom: none; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .meta { font-size: var(--text-xs); color: var(--text-subtle); margin-left: var(--space-2); }

    /* Capped so a label and the figure it explains stay in the same glance —
       a two-column table at full page width puts them a foot apart. */
    .working { margin-top: var(--space-3); max-width: 560px; }
    .working th {
      font-weight: 400;
      color: var(--text-muted);
      text-transform: none;
      letter-spacing: 0;
      font-size: var(--text-sm);
    }
    .working .total th, .working .total td { font-weight: 600; color: var(--text); }
    .working .total td { font-family: var(--font-data); }

    .headline { display: flex; flex-direction: column; gap: 4px; }
    /* The method is prose and is meant to be read, unlike the definitions it
       sits under — §7 requires it to be visible, so it is not small print. */
    .method {
      margin: var(--space-4) 0 0;
      padding: var(--space-3) var(--space-4);
      border-left: 3px solid var(--primary-500);
      background: var(--surface-sunken);
      border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
      font-size: var(--text-sm);
      line-height: 1.6;
    }

    @media print {
      .no-print { display: none; }
      .section { break-inside: avoid; }
      .card { box-shadow: none; }
    }
  `],
})
export class ReportComponent implements OnInit {
  private readonly api = inject(ApiService);

  readonly report = signal<RetentionDiagnostic | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.getDiagnostic().subscribe({
      next: (data) => this.report.set(data),
      error: () => this.error.set('Could not build your retention report.'),
    });
  }

  print(): void {
    window.print();
  }

  /**
   * Bar lengths are the shares the server sent, not recomputed here. Two places
   * dividing by the same denominator is two places that can disagree about what
   * a bar means.
   *
   * A null share means there is nobody to take a share of, so the bar is empty
   * rather than full — a filled bar against "0 of 0" would read as a finding.
   */
  barWidth(sharePercent: number | null): number {
    return sharePercent ?? 0;
  }

  shareText(sharePercent: number | null): string {
    return sharePercent === null ? 'no customers to share' : `${sharePercent}% of customers`;
  }

  /** Severity is carried by the word as well as the tint, never by colour alone. */
  riskLabel(band: OverdueBand): string {
    return band === 'LONG_OVERDUE' ? 'Long overdue'
      : band === 'WELL_PAST' ? 'Well past' : 'Just past';
  }

  riskClass(band: OverdueBand): string {
    return band === 'LONG_OVERDUE' ? 'pill-bad'
      : band === 'WELL_PAST' ? 'pill-warn' : 'pill-neutral';
  }
}
