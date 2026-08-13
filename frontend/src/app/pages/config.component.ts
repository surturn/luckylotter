import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { BusinessConfig } from '../core/api.models';
import { ToastService } from '../shared/toast.service';

/**
 * Trigger tuning and deal defaults (FR-6, US-1, US-3).
 *
 * The sensitivity multiplier is the hard part of this screen. `1.5` is precise
 * and meaningless to a café owner, so it is presented as three named choices
 * with a worked example that updates live — the raw number stays available for
 * anyone who wants it, but nobody has to reason in multipliers to use this.
 */
@Component({
  selector: 'app-config',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <h1>Trigger settings</h1>
    <p class="muted small" style="margin:4px 0 var(--space-5)">
      Controls how quickly a quiet customer is flagged, and what offer they're sent.
    </p>

    @if (error(); as message) {
      <div class="banner banner-error" role="alert">{{ message }}</div>
    }

    @if (loading()) {
      <div class="card" style="padding:var(--space-5)">
        @for (row of [1,2,3,4]; track row) {
          <div class="skeleton" style="margin:16px 0"></div>
        }
      </div>
    } @else {
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="grid">
        <section class="card block">
          <h2>When to flag a customer</h2>
          <p class="hint" style="margin-bottom:var(--space-4)">
            Each customer is judged against their own rhythm, not one fixed number of days —
            so a daily regular and a monthly regular are both flagged fairly.
          </p>

          <div class="field">
            <label for="sensitivity">Sensitivity</label>
            <select id="sensitivity" formControlName="sensitivityMultiplier">
              @for (preset of presets; track preset.value) {
                <option [value]="preset.value">{{ preset.label }}</option>
              }
            </select>
            <p class="hint">
              <strong>{{ example() }}</strong>
            </p>
          </div>

          <div class="row">
            <div class="field">
              <label for="min">Never flag before</label>
              <input id="min" type="number" min="1" max="365" formControlName="minThresholdDays"
                     [class.invalid]="clampInvalid()" />
              <p class="hint">days quiet</p>
            </div>
            <div class="field">
              <label for="max">Always flag by</label>
              <input id="max" type="number" min="1" max="365" formControlName="maxThresholdDays"
                     [class.invalid]="clampInvalid()" />
              <p class="hint">days quiet</p>
            </div>
          </div>

          @if (clampInvalid()) {
            <p class="error-text" role="alert">
              "Never flag before" must be less than or equal to "always flag by" — otherwise there's
              no window left in which anyone can be flagged.
            </p>
          }

          <!-- Visually separated and labelled, because it sits under the day
               fields and would otherwise read as something they control. -->
          <div class="fixed-rule">
            <span class="tag">Fixed</span>
            <span>
              Separately from the days above, a customer needs at least
              <strong>{{ config()?.minTransactions }} recorded visits</strong> before they have a
              rhythm worth measuring. Below that they're never flagged. This one isn't adjustable.
            </span>
          </div>
        </section>

        <section class="card block">
          <h2>How often the same person can get one</h2>
          <p class="hint" style="margin-bottom:var(--space-4)">
            After an offer reaches someone, they're left alone for a while before they can be
            flagged again. Without this, going quiet becomes a way to earn discounts, and your
            regulars learn to space their visits out.
          </p>

          <div class="row">
            <div class="field">
              <label for="cooldownDays">Leave them alone for at least</label>
              <input id="cooldownDays" type="number" min="0" max="365"
                     formControlName="offerCooldownDays" />
              <p class="hint">days</p>
            </div>
            <div class="field">
              <label for="cooldownMultiplier">Or this many times their usual gap</label>
              <input id="cooldownMultiplier" type="number" min="0" max="20" step="0.5"
                     formControlName="offerCooldownMultiplier" />
              <p class="hint">whichever is longer</p>
            </div>
          </div>

          <p class="hint">
            <strong>{{ cooldownExample() }}</strong>
          </p>
        </section>

        <section class="card block">
          <h2>How many you'll send in total</h2>
          <p class="hint" style="margin-bottom:var(--space-4)">
            A ceiling on the whole programme, so it can't cost more than you planned. Customers
            past the ceiling are still flagged and still shown to you — you'll see exactly who you
            didn't reach, marked "Budget reached".
          </p>

          <div class="row">
            <div class="field">
              <label for="cap">At most</label>
              <input id="cap" type="number" min="0" max="1000000"
                     formControlName="offerCapPerWindow" />
              <p class="hint">offers</p>
            </div>
            <div class="field">
              <label for="window">In any</label>
              <input id="window" type="number" min="1" max="365"
                     formControlName="offerBudgetWindowDays" />
              <p class="hint">days, counted rolling — not reset on the 1st</p>
            </div>
          </div>

          <p class="hint">
            <strong>{{ budgetExample() }}</strong>
          </p>
        </section>

        <section class="card block">
          <h2>What to offer them</h2>
          <p class="hint" style="margin-bottom:var(--space-4)">
            Applied to every offer generated from now on. Offers already sent keep the deal they were
            created with.
          </p>

          <div class="field">
            <label for="dealType">Deal type</label>
            <select id="dealType" formControlName="defaultDealType">
              <option value="PERCENT_OFF">Percentage off</option>
              <option value="FIXED_AMOUNT_OFF">Fixed amount off</option>
              <option value="FREE_ITEM">Free item</option>
            </select>
          </div>

          <div class="field">
            <label for="dealValue">{{ valueLabel() }}</label>
            <input id="dealValue" type="number" min="0.01" step="0.01" formControlName="defaultDealValue"
                   [class.invalid]="invalid('defaultDealValue')" />
            @if (invalid('defaultDealValue')) {
              <p class="error-text">Enter a value greater than zero.</p>
            }
          </div>

          <button type="submit" class="btn-primary" [disabled]="busy() || clampInvalid()">
            {{ busy() ? 'Saving…' : 'Save settings' }}
          </button>
        </section>
      </form>
    }
  `,
  styles: [`
    .grid {
      display: grid;
      gap: var(--space-4);
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      align-items: start;
    }
    .block { padding: var(--space-5); }
    h2 { margin-bottom: var(--space-2); }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); }
  `],
})
export class ConfigComponent {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly presets = [
    { value: 1.2, label: 'Sensitive — flag soon after they slip' },
    { value: 1.5, label: 'Balanced — recommended' },
    { value: 2, label: 'Relaxed — only when clearly gone' },
  ];

  readonly config = signal<BusinessConfig | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly form = inject(FormBuilder).nonNullable.group({
    sensitivityMultiplier: [1.5, Validators.required],
    minThresholdDays: [3, [Validators.required, Validators.min(1), Validators.max(365)]],
    maxThresholdDays: [60, [Validators.required, Validators.min(1), Validators.max(365)]],
    offerCooldownDays: [30, [Validators.required, Validators.min(0), Validators.max(365)]],
    offerCooldownMultiplier: [3, [Validators.required, Validators.min(0), Validators.max(20)]],
    offerCapPerWindow: [100, [Validators.required, Validators.min(0), Validators.max(1000000)]],
    offerBudgetWindowDays: [30, [Validators.required, Validators.min(1), Validators.max(365)]],
    defaultDealType: ['PERCENT_OFF' as BusinessConfig['defaultDealType'], Validators.required],
    defaultDealValue: [25, [Validators.required, Validators.min(0.01)]],
  });

  constructor() {
    this.api.getConfig().subscribe({
      next: (config) => {
        this.config.set(config);
        this.form.patchValue(config);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load your settings. Refresh to try again.');
      },
    });
  }

  /**
   * The same clamp the backend applies, worked through a concrete customer.
   * Duplicated here deliberately: showing the admin the real consequence before
   * they save beats making them guess and check.
   */
  example(): string {
    const { sensitivityMultiplier, minThresholdDays, maxThresholdDays } = this.form.getRawValue();
    const typicalGap = 7;
    const raw = typicalGap * Number(sensitivityMultiplier);
    const min = Number(minThresholdDays);
    const max = Number(maxThresholdDays);
    const clamped = Math.min(Math.max(raw, min), max);
    const base = `A customer who normally visits every ${typicalGap} days is flagged after ${clamped.toFixed(0)} quiet days`;

    // When a clamp overrides the multiplier, say which one — otherwise changing
    // the sensitivity appears to do nothing and the screen looks broken.
    if (clamped === min && raw < min) {
      return `${base} — held there by "never flag before", since ${sensitivityMultiplier}× their usual gap would be ${raw.toFixed(0)}.`;
    }
    if (clamped === max && raw > max) {
      return `${base} — capped by "always flag by", since ${sensitivityMultiplier}× their usual gap would be ${raw.toFixed(0)}.`;
    }
    return `${base}.`;
  }

  /**
   * The cooldown worked through the same 7-day customer as {@link example}, so
   * the two numbers on this screen can be read against each other. Says which
   * of the two inputs won, for the same reason the threshold example does: a
   * field that appears to do nothing reads as broken.
   */
  cooldownExample(): string {
    const { offerCooldownDays, offerCooldownMultiplier } = this.form.getRawValue();
    const typicalGap = 7;
    const floor = Number(offerCooldownDays);
    const scaled = typicalGap * Number(offerCooldownMultiplier);
    const applied = Math.max(floor, scaled);

    if (applied === 0) {
      return 'That same customer can be flagged again immediately — there is no cooldown at all.';
    }
    const base = `That same customer can't get another offer for ${applied.toFixed(0)} days`;
    if (scaled > floor) {
      return `${base} — ${offerCooldownMultiplier}× their usual 7-day gap, which is longer than your ${floor}-day minimum.`;
    }
    return `${base} — your ${floor}-day minimum, since ${offerCooldownMultiplier}× their usual gap would only be ${scaled.toFixed(0)}.`;
  }

  /**
   * The ceiling stated as money where that is actually knowable.
   *
   * <p>Only for `FIXED_AMOUNT_OFF`: because Phase 1 pins one static deal per
   * business, every offer carries the same value, so cap × value is the real
   * exposure rather than an estimate. A percentage needs the basket it applies
   * to and a free item needs its cost, and neither is recorded — so those say
   * nothing rather than guess. Nothing here counts redemption, which isn't
   * tracked yet; this is the most it can cost, not what it will cost.
   */
  budgetExample(): string {
    const { offerCapPerWindow, offerBudgetWindowDays, defaultDealType, defaultDealValue } =
      this.form.getRawValue();
    const cap = Number(offerCapPerWindow);
    const days = Number(offerBudgetWindowDays);

    if (cap === 0) {
      return 'No offers will be sent at all — every flagged customer will be marked "Budget reached".';
    }
    const base = `At most ${cap} offers in any ${days} days`;
    if (defaultDealType === 'FIXED_AMOUNT_OFF') {
      const exposure = cap * Number(defaultDealValue);
      return `${base} — up to ${exposure.toLocaleString()} in discounts if every one of them is claimed.`;
    }
    return `${base}.`;
  }

  valueLabel(): string {
    switch (this.form.getRawValue().defaultDealType) {
      case 'PERCENT_OFF':
        return 'Percentage off';
      case 'FIXED_AMOUNT_OFF':
        return 'Amount off';
      default:
        return 'Number of free items';
    }
  }

  clampInvalid(): boolean {
    const { minThresholdDays, maxThresholdDays } = this.form.getRawValue();
    return Number(minThresholdDays) > Number(maxThresholdDays);
  }

  invalid(control: 'defaultDealValue'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  submit(): void {
    if (this.form.invalid || this.clampInvalid()) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);

    const value = this.form.getRawValue();
    this.api.updateConfig({
      sensitivityMultiplier: Number(value.sensitivityMultiplier),
      minThresholdDays: Number(value.minThresholdDays),
      maxThresholdDays: Number(value.maxThresholdDays),
      offerCooldownDays: Number(value.offerCooldownDays),
      offerCooldownMultiplier: Number(value.offerCooldownMultiplier),
      offerCapPerWindow: Number(value.offerCapPerWindow),
      offerBudgetWindowDays: Number(value.offerBudgetWindowDays),
      defaultDealType: value.defaultDealType,
      defaultDealValue: Number(value.defaultDealValue),
    }).subscribe({
      next: (config) => {
        this.config.set(config);
        this.busy.set(false);
        this.toasts.success('Settings saved.');
      },
      // A save failure stays an inline banner: the admin has to do something
      // about it, and a message that clears itself after four seconds is the
      // wrong carrier for that.
      error: (response) => {
        this.busy.set(false);
        this.error.set(
          response.status === 400
            ? response.error?.message ?? 'Those settings were rejected. Check the values and try again.'
            : 'Could not save your settings. Try again in a moment.'
        );
      },
    });
  }
}
