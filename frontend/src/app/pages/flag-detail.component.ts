import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { FlagDetail, OfferFailureCode } from '../core/api.models';
import { StatusPillComponent } from '../shared/status-pill.component';
import { VisitSparklineComponent } from '../shared/visit-sparkline.component';

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
  imports: [RouterLink, DatePipe, DecimalPipe, StatusPillComponent, VisitSparklineComponent],
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

      <section class="card block timeline">
        <h2>Visit rhythm</h2>
        <p class="hint" style="margin:0 0 var(--space-4)">
          Every dot is a real recorded visit. The shaded stretch is the silence that
          triggered the flag.
        </p>

        @if (visitsFailed()) {
          <p class="muted small" style="margin:0">
            Couldn't load this customer's visit history.
          </p>
        } @else if (visits() === null) {
          <div class="skeleton" style="height:64px"></div>
        } @else {
          <app-visit-sparkline [fluid]="true" [width]="640" [height]="64" [radius]="5"
                               [visits]="visits()!" [flaggedAt]="detail.flaggedAt" />

          <!-- Endpoints labelled, so the axis is readable without hovering. -->
          @if (visits()!.length > 0) {
            <div class="axis-labels mono small muted">
              <span>{{ visits()![0] | date: 'd MMM yyyy' }}</span>
              <span>Flagged {{ detail.flaggedAt | date: 'd MMM yyyy' }}</span>
            </div>
          }

          <ul class="legend small muted">
            <li><span class="key key-visit"></span>Visit</li>
            <li><span class="key key-gap"></span>Quiet stretch</li>
            <li><span class="key key-flag"></span>Flag raised</li>
          </ul>
        }
      </section>

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
              @if (detail.offerFailureCode; as code) {
                <dt>Reason</dt>
                <dd class="reason">
                  {{ failureLabel(code) }}
                  <!-- The raw code stays visible in small print: it is what
                       support will ask for, and the sentence above it is a
                       translation, not a replacement. -->
                  <span class="mono small muted">{{ code }}</span>
                </dd>
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
    .timeline { margin-bottom: var(--space-4); }
    .timeline h2 { margin-bottom: var(--space-1); }
    .axis-labels {
      display: flex;
      justify-content: space-between;
      gap: var(--space-4);
      margin-top: var(--space-2);
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-4);
      list-style: none;
      margin: var(--space-4) 0 0;
      padding: 0;
    }
    .legend li { display: flex; align-items: center; gap: var(--space-2); }
    .key { width: 10px; height: 10px; border-radius: var(--radius-full); flex: none; }
    .key-visit { background: var(--primary-600); }
    .key-gap { background: var(--warning-50); border: 1px solid var(--warning-600); border-radius: 2px; }
    .key-flag { width: 3px; height: 12px; border-radius: 2px; background: var(--danger-600); }
    /* The sentence leads; the code sits under it rather than beside it, so a
       long reason doesn't push the code off the edge on a narrow screen. */
    .reason { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
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

  /** Real transaction timestamps; null until they arrive. */
  readonly visits = signal<string[] | null>(null);
  readonly visitsFailed = signal(false);

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

    // Fetched alongside the flag rather than after it: the two are independent
    // reads, and the timeline is the slower one to look at anyway.
    this.api.getFlagVisits(id).subscribe({
      // Sorted here because the axis labels read the ends of the array; the
      // chart sorts its own copy, but this component can't assume it did.
      next: (history) =>
        this.visits.set(
          history.visits
            .map((visit) => visit.occurredAt)
            .sort((a, b) => new Date(a).getTime() - new Date(b).getTime())
        ),
      // The timeline is evidence for a decision already explained above it.
      // Losing it degrades the page; it doesn't break it.
      error: () => this.visitsFailed.set(true),
    });
  }

  /**
   * The stored code in words. Deliberately says what the admin can do about it,
   * because the code alone ("SENDER_REJECTED") tells them nothing actionable.
   */
  failureLabel(code: OfferFailureCode): string {
    switch (code) {
      case 'MISSING_CONTACT_DETAILS':
        return 'No email or phone was on file for this customer.';
      case 'INVALID_EMAIL_ADDRESS':
        return 'The email address on file was rejected as malformed.';
      case 'INVALID_PHONE_NUMBER':
        return 'The phone number on file was rejected as malformed.';
      case 'SENDER_TIMEOUT':
        return 'The mail service did not respond in time. This will be retried.';
      case 'SENDER_UNAVAILABLE':
        return 'The mail service was unavailable. This will be retried.';
      case 'SENDER_REJECTED':
        return 'The mail service accepted the request but refused to send it.';
      default:
        return 'Sending failed for an unexpected reason.';
    }
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
