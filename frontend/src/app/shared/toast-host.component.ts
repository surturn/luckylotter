import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/**
 * Where toasts appear. Mounted once, in the shell.
 *
 * The live region is the wrapper and it is always in the DOM — a region that
 * only exists while a toast does is announced inconsistently, because the
 * screen reader never observed it empty first.
 */
@Component({
  selector: 'app-toast-host',
  standalone: true,
  template: `
    <div class="stack" role="status" aria-live="polite" aria-atomic="false">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" [class.toast-error]="toast.tone === 'error'">
          <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            @if (toast.tone === 'error') {
              <path fill-rule="evenodd" clip-rule="evenodd"
                    d="M10 1.5a8.5 8.5 0 100 17 8.5 8.5 0 000-17zM9 5.75a1 1 0 112 0v5a1 1 0 11-2 0v-5zM10 15a1.15 1.15 0 110-2.3 1.15 1.15 0 010 2.3z" />
            } @else {
              <path fill-rule="evenodd" clip-rule="evenodd"
                    d="M10 1.5a8.5 8.5 0 100 17 8.5 8.5 0 000-17zm4.03 6.28a1 1 0 00-1.56-1.25l-3.4 4.25-1.86-1.86a1 1 0 10-1.42 1.42l2.65 2.65a1 1 0 001.49-.08l4.1-5.13z" />
            }
          </svg>
          <span>{{ toast.message }}</span>
          <button type="button" class="toast-dismiss" aria-label="Dismiss this message"
                  (click)="toasts.dismiss(toast.id)">&times;</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .stack {
      position: fixed;
      z-index: 50;
      bottom: var(--space-5);
      right: var(--space-5);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      /* The container spans the corner but must not swallow clicks on the page
         underneath it; the toasts themselves take pointer events back. */
      pointer-events: none;
    }
    .toast {
      pointer-events: auto;
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      max-width: min(380px, calc(100vw - var(--space-8)));
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius);
      border-left: 3px solid var(--success-600);
      background: var(--surface);
      color: var(--text);
      font-size: var(--text-sm);
      box-shadow: var(--shadow-md);
      animation: toast-in 200ms cubic-bezier(0.4, 0, 0.2, 1);
    }
    .toast svg { flex: none; margin-top: 2px; color: var(--success-600); }
    .toast > span { flex: 1; }
    .toast-error { border-left-color: var(--danger-600); }
    .toast-error svg { color: var(--danger-600); }
    .toast-dismiss {
      flex: none;
      min-height: 0;
      padding: 0 var(--space-1);
      margin-top: -2px;
      background: transparent;
      border: none;
      color: var(--text-subtle);
      font-size: var(--text-lg);
      line-height: 1.2;
    }
    .toast-dismiss:hover { color: var(--text); }

    @keyframes toast-in {
      from { opacity: 0; transform: translateY(8px); }
      to   { opacity: 1; transform: none; }
    }

    /* On a phone the corner is where the thumb is; span the width instead. */
    @media (max-width: 600px) {
      .stack { left: var(--space-4); right: var(--space-4); bottom: var(--space-4); }
      .toast { max-width: none; }
    }
  `],
})
export class ToastHostComponent {
  readonly toasts = inject(ToastService);
}
