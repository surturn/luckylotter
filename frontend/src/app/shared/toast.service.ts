import { Injectable, signal } from '@angular/core';

export type ToastTone = 'success' | 'error';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
}

/**
 * Transient confirmations that don't belong in the page body.
 *
 * A toast is for an outcome the admin already expects — they pressed Save, it
 * saved. Anything they need to *act* on stays an inline banner, because a
 * message that disappears on a timer is a message that can be missed.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  /** Long enough to read a short sentence, short enough not to linger. */
  private static readonly DISMISS_AFTER_MS = 4000;

  private nextId = 0;

  readonly toasts = signal<Toast[]>([]);

  success(message: string): void {
    this.push(message, 'success');
  }

  error(message: string): void {
    this.push(message, 'error');
  }

  dismiss(id: number): void {
    this.toasts.update((current) => current.filter((toast) => toast.id !== id));
  }

  private push(message: string, tone: ToastTone): void {
    const id = this.nextId++;
    this.toasts.update((current) => [...current, { id, message, tone }]);
    setTimeout(() => this.dismiss(id), ToastService.DISMISS_AFTER_MS);
  }
}
