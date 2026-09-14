import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { FlagSort, FlagStatus, FlagSummary, FlagVisits, OverdueBand, PageResponse } from '../core/api.models';
import { StatusPillComponent } from '../shared/status-pill.component';
import { VisitSparklineComponent } from '../shared/visit-sparkline.component';

/**
 * The flagged-customer list as the main action screen (FR-7, US-2, §5).
 *
 * The screen's question is "who should I act on today", and the default order
 * answers it: risk, value and how the customer has answered offers before,
 * combined. Everything the operator needs to judge a row sits in the row — how
 * long they have been gone against their own usual gap, what they are worth
 * next to this business's average customer, what happened the last times anyone
 * tried, and what is stopping this one from being contacted now.
 *
 * The score is arithmetic over the business's own transaction history. The page
 * says so, because a number this prominent would otherwise read as a prediction.
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

    <div class="controls">
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

      <label class="sort">
        <span class="small muted">Order by</span>
        <select [value]="sort()" (change)="setSort($event)">
          @for (option of sorts; track option.value) {
            <option [value]="option.value">{{ option.label }}</option>
          }
        </select>
      </label>
    </div>

    <!-- Stated before the numbers, not after: the score decides the order of
         this page, and "priority" on its own would read as a prediction. -->
    <details class="def">
      <summary>How this order is worked out</summary>
      <div>
        <p>
          Each customer scores 0–100 on three things. <strong>How far past their own usual gap they are</strong>
          counts for 40% — measured against their threshold, which is their own rhythm scaled by your
          sensitivity setting, not a fixed number of days. <strong>What they are worth</strong> counts for 40%:
          their lifetime spend against what an average customer of yours spends. <strong>How they have answered
          offers before</strong> counts for the remaining 20%.
        </p>
        <p>
          A customer who has never been offered anything scores 50 on that third part rather than 0 — never
          having been asked is not the same as having said no, and scoring it 0 would bury every new customer
          under everyone who has ever replied. A customer who has already come back scores nothing and sorts
          last, because that row is history and this screen is about today.
        </p>
        <p>
          It is arithmetic over your own transaction history. It is not a prediction and not a model.
        </p>
      </div>
    </details>

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
              <caption class="visually-hidden">Customers flagged as at risk, most worth acting on first</caption>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col" class="numeric">Priority</th>
                  <th scope="col">Visit rhythm</th>
                  <th scope="col">Last visit</th>
                  <th scope="col" class="numeric">Days overdue</th>
                  <th scope="col" class="numeric">Lifetime spend</th>
                  <th scope="col">Offers so far</th>
                  <th scope="col">What to do</th>
                  <th scope="col"><span class="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (flag of data.items; track flag.flagId) {
                  <tr>
                    <td>
                      <span class="customer">
                        <!-- Initial from the name when there is one, else from
                             the till reference, so the column never has a hole
                             where a customer the POS didn't name should be. -->
                        <span class="avatar" aria-hidden="true">{{ initialFor(flag) }}</span>
                        <span class="who">
                          <span class="mono ref">{{ flag.customerRef }}</span>
                          @if (flag.customerName) {
                            <span class="name">{{ flag.customerName }}</span>
                          }
                          @if (flag.usualItem) {
                            <span class="sub">usually {{ flag.usualItem }}</span>
                          }
                        </span>
                      </span>
                    </td>
                    <!-- The combination, with its parts underneath: a score you
                         can't take apart is a number the operator has to
                         either trust or ignore. -->
                    <td class="numeric">
                      <span class="score mono">{{ flag.priorityScore }}</span>
                      <span class="sub mono"
                            [title]="priorityTitle(flag)">
                        {{ flag.priority.risk }}·{{ flag.priority.value }}·{{ flag.priority.history }}
                      </span>
                    </td>
                    <td class="rhythm">
                      @if (visitsFor(flag.flagId); as history) {
                        <app-visit-sparkline [visits]="history.visits" [flaggedAt]="history.flaggedAt" />
                      } @else {
                        <span class="skeleton rhythm-placeholder"></span>
                      }
                    </td>
                    <td>
                      <span class="mono">{{ flag.lastVisitAt ? (flag.lastVisitAt | date: 'd MMM yyyy') : '—' }}</span>
                      <span class="sub">usually every {{ flag.avgIntervalDaysAtFlag | number: '1.0-0' }} days</span>
                    </td>
                    <!-- Rounded so the column scans; the exact threshold stays in
                         the tooltip, since it is the number that actually fired. -->
                    <td class="numeric">
                      @if (flag.daysOverdue !== null && flag.riskBand) {
                        <span class="mono overdue" [title]="overdueTitle(flag)">
                          {{ flag.daysOverdue | number: '1.0-0' }} days
                        </span>
                        <span class="band" [class]="'band ' + bandClass(flag.riskBand)">{{ bandLabel(flag.riskBand) }}</span>
                      } @else {
                        <span class="muted">—</span>
                        <!-- Not "back on their rhythm": one visit answered the
                             offer, which is not the same as a rhythm resumed,
                             and this column would be claiming the latter. -->
                        <span class="sub">came back</span>
                      }
                    </td>
                    <td class="numeric">
                      <span class="mono">
                        {{ flag.totalSpend | number: '1.2-2' }}
                        @if (currency(); as code) {
                          <span class="code">{{ code }}</span>
                        }
                      </span>
                      <span class="sub">
                        {{ flag.visitCount }} {{ flag.visitCount === 1 ? 'visit' : 'visits' }}
                        @if (flag.visitCount > 0) {
                          · avg {{ flag.averageTransactionValue | number: '1.2-2' }}
                        }
                      </span>
                    </td>
                    <td>
                      <span>{{ responseLabel(flag) }}</span>
                      @if (flag.priorInterventions > 0) {
                        <span class="sub">{{ flag.priorReturns }} came back</span>
                      }
                    </td>
                    <td>
                      <span>{{ flag.recommendedAction }}</span>
                      <span class="sub"><app-status-pill [status]="flag.offerStatus" /></span>
                    </td>
                    <td><a [routerLink]="['/flags', flag.flagId]">Why?</a></td>
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
    .controls {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
      margin-bottom: var(--space-4);
    }
    .filters { display: flex; gap: var(--space-2); flex-wrap: wrap; }
    .filters button { min-height: 36px; padding: 0 var(--space-3); font-size: var(--text-sm); }
    /* The global label rule makes this a block with a bottom margin; this one
       is a flex row beside the filter buttons, so both are undone here. */
    .sort { display: flex; align-items: center; gap: var(--space-2); margin-left: auto; margin-bottom: 0; }
    .sort select { width: auto; min-width: 200px; min-height: 36px; padding-top: 0; padding-bottom: 0; font-size: var(--text-sm); }
    .code { font-family: var(--font-data); font-size: var(--text-xs); color: var(--text-muted); }
    .def { margin-bottom: var(--space-4); }
    .def summary {
      cursor: pointer;
      font-size: var(--text-sm);
      color: var(--primary-600);
      padding: var(--space-1) 0;
    }
    .def p { margin: var(--space-2) 0 0; font-size: var(--text-sm); color: var(--text-muted); max-width: 78ch; }
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

    .customer { display: flex; align-items: flex-start; gap: var(--space-3); }
    .avatar {
      flex: none;
      width: 30px;
      height: 30px;
      display: grid;
      place-items: center;
      border-radius: var(--radius-full);
      background: var(--primary-50);
      color: var(--primary-600);
      font-size: var(--text-xs);
      font-weight: 700;
    }
    .who { display: flex; flex-direction: column; line-height: 1.35; min-width: 0; }
    /* The till reference leads: it is the identifier the admin can search their
       own POS for, and it is present for every customer. The name sits under it
       as the human-readable half. */
    .ref { font-size: var(--text-sm); font-weight: 600; }
    .name {
      font-size: var(--text-xs);
      color: var(--text-muted);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* Supporting figures: present enough to judge with, quiet enough that the
       column the operator is actually scanning still leads. */
    .sub {
      display: block;
      font-size: var(--text-xs);
      color: var(--text-muted);
      line-height: 1.35;
    }
    .score { font-size: var(--text-lg); font-weight: 700; display: block; }
    .overdue { font-weight: 600; display: block; }
    .band {
      display: inline-block;
      margin-top: 2px;
      padding: 1px 6px;
      border-radius: var(--radius-full);
      font-size: var(--text-xs);
      white-space: nowrap;
    }
    /* Same three steps the overview's chart uses, so a customer called "long
       overdue" on one screen isn't "high risk" on another. */
    .band-just { background: var(--neutral-100); color: var(--text-muted); }
    .band-well { background: #fdf3d7; color: #8a6100; }
    .band-long { background: var(--danger-50); color: var(--danger-700); }

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

  readonly sorts: { label: string; value: FlagSort }[] = [
    { label: 'What to act on first', value: 'PRIORITY' },
    { label: 'Most overdue', value: 'OVERDUE' },
    { label: 'Highest spend', value: 'VALUE' },
    { label: 'Recently flagged', value: 'RECENT' },
  ];

  readonly page = signal<PageResponse<FlagSummary> | null>(null);
  readonly status = signal<FlagStatus | null>(null);
  readonly sort = signal<FlagSort>('PRIORITY');
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly scanning = signal(false);
  readonly error = signal<string | null>(null);
  readonly banner = signal<string | null>(null);
  readonly uncontactable = signal(0);
  readonly contactWarningHidden = signal(false);

  /**
   * Only used to label the spend column. A figure with no currency beside it
   * invites the reader to assume one, and the business's own is a request away.
   */
  readonly currency = signal<string | null>(null);

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

  /**
   * Falls back to the till reference when the POS gave no name, so every row
   * gets an avatar. Taking the last character of the ref rather than the first
   * because references share a prefix — every "POS-…" would otherwise render
   * the same letter and the avatars would stop distinguishing anything.
   */
  initialFor(flag: FlagSummary): string {
    const name = flag.customerName?.trim();
    if (name) {
      return name.charAt(0).toUpperCase();
    }
    const ref = flag.customerRef.trim();
    return ref ? ref.charAt(ref.length - 1).toUpperCase() : '?';
  }

  bandLabel(band: OverdueBand): string {
    switch (band) {
      case 'JUST_PAST':
        return 'just past';
      case 'WELL_PAST':
        return 'well past';
      case 'LONG_OVERDUE':
        return 'long overdue';
    }
  }

  bandClass(band: OverdueBand): string {
    switch (band) {
      case 'JUST_PAST':
        return 'band-just';
      case 'WELL_PAST':
        return 'band-well';
      case 'LONG_OVERDUE':
        return 'band-long';
    }
  }

  /**
   * Spells the components out on hover. The three numbers beside the score are
   * unlabelled by design — they only mean anything against each other — so the
   * tooltip is where they get named.
   */
  priorityTitle(flag: FlagSummary): string {
    if (flag.status === 'RESOLVED') {
      // Scored zero on every component. Reading those zeros out as "0 for what
      // they are worth" would describe a customer who came back as worthless
      // rather than a row that is simply no longer being ranked.
      return 'They came back, so this row is history and is not scored.';
    }
    return `${flag.priority.risk} of 100 for how overdue they are, `
      + `${flag.priority.value} for what they are worth, `
      + `${flag.priority.history} for how they have answered offers`;
  }

  /**
   * The threshold that actually fired the flag. The column rounds to whole
   * days because that is what scans; the tooltip keeps the exact number, since
   * the rounded one is not the number the trigger compared against.
   */
  overdueTitle(flag: FlagSummary): string {
    return `${flag.thresholdDaysApplied.toFixed(1)} quiet days is this customer's threshold`;
  }

  /**
   * How many offers this customer has had, and what came of them. Kept separate
   * from the current offer's status: this is the record behind the customer, not
   * the state of the intervention in front of the operator today.
   */
  responseLabel(flag: FlagSummary): string {
    if (flag.priorInterventions === 0) {
      return 'Never offered before';
    }
    const noun = flag.priorInterventions === 1 ? 'offer' : 'offers';
    return `${flag.priorInterventions} ${noun} before this`;
  }

  setStatus(status: FlagStatus | null): void {
    this.status.set(status);
    this.load(0);
  }

  setSort(event: Event): void {
    this.sort.set((event.target as HTMLSelectElement).value as FlagSort);
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
    this.api.listFlags(page, this.pageSize, this.status(), this.sort()).subscribe({
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
    this.api.getConfig().subscribe({
      next: (config) => this.currency.set(config.currencyCode),
      // The code is a label on a column that still has correct numbers in it.
      error: () => this.currency.set(null),
    });
  }
}
