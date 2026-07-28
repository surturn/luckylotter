import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { FlagDetail } from '../core/api.models';
import { StatusPillComponent } from '../shared/status-pill.component';

/**
 * One flag in full (FR-7).
 *
 * Leads with why the customer was flagged in plain language, because the
 * snapshot numbers are the part an admin has to trust before acting on
 * anything else here.
 */
@Component({
  selector: 'app-flag-detail',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, StatusPillComponent],
  template: `
    <a routerLink="/flags" class="back">← All flagged customers</a>

    @if (loading()) {
      <div class="card" style="padding:var(--space-5)">
        @for (row of [1,2,3]; track row) {
          <div class="skeleton" style="margin:14px 0"></div>
        }
      </div>
    } @else if (error()) {
      <div class="banner banner-error" role="alert">{{ error() }}</div>
    } @else {
      @if (flag(); as detail) {
      <div class="head">
        <h1 class="mono">{{ detail.customerRef }}</h1>
        <app-status-pill [status]="detail.status" />
      </div>

      <div class="card explain">
        <p style="margin:0">
          Normally visits about every
          <strong>{{ detail.avgIntervalDaysAtFlag | number: '1.0-1' }} days</strong>,
          so they were flagged once
          <strong>{{ detail.thresholdDaysApplied | number: '1.0-1' }} days</strong>
          passed with no visit.
          @if (detail.status === 'RESOLVED') {
            They have since come back — this flag closed on
            {{ detail.resolvedAt | date: 'd MMM yyyy' }}.
          }
        </p>
      </div>

      <div class="grid">
        <section class="card block">
          <h2>Customer</h2>
          <dl>
            <dt>Last visit</dt>
            <dd class="mono">{{ detail.lastVisitAt ? (detail.lastVisitAt | date: 'd MMM yyyy') : '—' }}</dd>
            <dt>First seen</dt>
            <dd class="mono">{{ detail.firstSeenAt | date: 'd MMM yyyy' }}</dd>
            <dt>Visits recorded</dt>
            <dd class="mono">{{ detail.transactionCount }}</dd>
            <dt>Current usual gap</dt>
            <dd class="mono">
              {{ detail.avgIntervalDays ? (detail.avgIntervalDays | number: '1.0-1') + ' days' : 'Not enough visits' }}
            </dd>
            <dt>Contactable</dt>
            <dd>
              @if (detail.contactable) { Yes } @else {
                <span class="pill pill-warn">No email or phone</span>
              }
            </dd>
          </dl>
        </section>

        <section class="card block">
          <h2>Offer</h2>
          @if (detail.offerId) {
            <dl>
              <dt>Deal</dt>
              <dd>{{ dealLabel(detail) }}</dd>
              <dt>Status</dt>
              <dd><app-status-pill [status]="detail.offerStatus" /></dd>
              @if (detail.redemptionCode) {
                <dt>Code</dt>
                <dd><span class="code">{{ detail.redemptionCode }}</span></dd>
              }
              <dt>Sent</dt>
              <dd class="mono">{{ detail.offerSentAt ? (detail.offerSentAt | date: 'd MMM yyyy, HH:mm') : '—' }}</dd>
              @if (detail.offerFailureCode) {
                <dt>Reason</dt>
                <dd class="mono small">{{ detail.offerFailureCode }}</dd>
              }
            </dl>
            @if (detail.offerStatus === 'NO_CONTACT') {
              <!-- Ranked above the surrounding detail: this offer is finished
                   and undeliverable, and only the business can unblock it. -->
              <div class="callout-blocked" role="alert">
                <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" clip-rule="evenodd"
                        d="M10 1.5a8.5 8.5 0 100 17 8.5 8.5 0 000-17zM9 5.75a1 1 0 112 0v5a1 1 0 11-2 0v-5zM10 15a1.15 1.15 0 110-2.3 1.15 1.15 0 010 2.3z" />
                </svg>
                <span>
                  <strong>This offer can't be delivered.</strong>
                  Your POS data has no email or phone for this customer, so nothing was sent and
                  nothing will retry. Include contact details on their future transactions and the
                  next offer will reach them.
                </span>
              </div>
            }
          } @else {
            <p class="muted small" style="margin:0">No offer was generated for this flag.</p>
          }
        </section>
      </div>
      }
    }
  `,
  styles: [`
    .back {
      display: inline-block;
      margin-bottom: var(--space-4);
      text-decoration: none;
      font-size: var(--text-sm);
      color: var(--text-muted);
    }
    .back:hover { color: var(--primary-600); }
    .head {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-4);
      flex-wrap: wrap;
    }
    .explain {
      padding: var(--space-4);
      margin-bottom: var(--space-4);
      border-left: 3px solid var(--primary-600);
    }
    .grid {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    }
    .block { padding: var(--space-4) var(--space-5) var(--space-5); }
    h2 { margin-bottom: var(--space-3); }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--space-2) var(--space-4);
      margin: 0;
      align-items: baseline;
    }
    dt { color: var(--text-muted); font-size: var(--text-sm); }
    dd { margin: 0; text-align: right; }
    /* The code is what the customer reads out at the counter, so it gets the
       data face and enough tracking to be transcribed without errors. */
    .code {
      font-family: var(--font-data);
      font-weight: 600;
      letter-spacing: 0.08em;
      background: var(--primary-50);
      color: var(--primary-700);
      padding: 2px 8px;
      border-radius: var(--radius-sm);
    }
  `],
})
export class FlagDetailComponent {
  private readonly api = inject(ApiService);

  readonly flag = signal<FlagDetail | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor() {
    const id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
    this.api.getFlag(id).subscribe({
      next: (detail) => {
        this.flag.set(detail);
        this.loading.set(false);
      },
      error: (response) => {
        this.loading.set(false);
        this.error.set(
          response.status === 404
            ? 'That flag no longer exists.'
            : 'Could not load this flag. Refresh to try again.'
        );
      },
    });
  }

  dealLabel(detail: FlagDetail): string {
    switch (detail.dealType) {
      case 'PERCENT_OFF':
        return `${detail.dealValue}% off`;
      case 'FIXED_AMOUNT_OFF':
        return `${detail.dealValue} off`;
      case 'FREE_ITEM':
        return `${detail.dealValue} free item(s)`;
      default:
        return '—';
    }
  }
}
