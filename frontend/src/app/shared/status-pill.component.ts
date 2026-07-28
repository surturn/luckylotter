import { Component, Input } from '@angular/core';
import { FlagStatus, OfferStatus } from '../core/api.models';

/**
 * Status as a labelled pill.
 *
 * `NO_CONTACT` gets its own amber treatment rather than sharing red with
 * `FAILED`: a failed send is a problem with the system, an un-contactable
 * customer is a gap in the business's own POS data, and the two need different
 * responses from the admin (FR-5).
 */
@Component({
  selector: 'app-status-pill',
  standalone: true,
  template: `<span class="pill" [class]="'pill ' + cssClass" [attr.aria-label]="ariaLabel">{{ label }}</span>`,
})
export class StatusPillComponent {
  @Input({ required: true }) status!: OfferStatus | FlagStatus | null;

  get label(): string {
    switch (this.status) {
      case null:
      case undefined:
        return 'No offer';
      case 'NO_CONTACT':
        return 'No contact details';
      default:
        return this.status.charAt(0) + this.status.slice(1).toLowerCase();
    }
  }

  get ariaLabel(): string {
    return this.status === 'NO_CONTACT'
      ? 'No contact details on file — this offer cannot be sent'
      : this.label;
  }

  get cssClass(): string {
    switch (this.status) {
      case 'SENT':
      case 'RESOLVED':
        return 'pill-good';
      case 'NO_CONTACT':
      case 'PENDING':
        return 'pill-warn';
      case 'FAILED':
        return 'pill-bad';
      default:
        return 'pill-neutral';
    }
  }
}
