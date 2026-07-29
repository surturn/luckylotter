import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { ToastHostComponent } from './shared/toast-host.component';

/**
 * Frame around the authenticated views: brand, tenant context, nav, sign-out.
 *
 * <p><b>Top nav, not a sidebar.</b> Four destinations don't earn a permanent
 * 240px column, and the two screens that matter — the flag table and the visit
 * timeline — are both horizontal-hungry. A sidebar would spend width on
 * navigation that the content is actually short of. Revisit if the nav passes
 * roughly seven entries or grows a second level.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHostComponent],
  template: `
    <header class="top">
      <div class="bar">
        <a class="brand" routerLink="/overview">
          <!-- A break in a rhythm, which is the whole product: even beats, one
               missed, then the mark that says it was noticed. -->
          <svg class="mark" width="28" height="28" viewBox="0 0 28 28" fill="none" aria-hidden="true">
            <rect width="28" height="28" rx="8" fill="url(#luck-mark)" />
            <g stroke="#fff" stroke-width="2" stroke-linecap="round">
              <path d="M6 18v-4" opacity="0.55" />
              <path d="M10.5 18v-6" opacity="0.75" />
              <path d="M15 18v-8" />
              <path d="M22 8.5v11" stroke-dasharray="0.5 3.5" />
            </g>
            <defs>
              <!-- Stop colours come from CSS, not attributes: stop-color as a
                   presentation attribute does not resolve var(). -->
              <linearGradient id="luck-mark" x1="0" y1="0" x2="28" y2="28">
                <stop class="stop-from" />
                <stop class="stop-to" offset="1" />
              </linearGradient>
            </defs>
          </svg>
          <strong>LuckLotter</strong>
          <span class="visually-hidden">— go to the overview</span>
        </a>

        <nav aria-label="Main">
          <a routerLink="/overview" routerLinkActive="active">Overview</a>
          <a routerLink="/flags" routerLinkActive="active">Flagged customers</a>
          <a routerLink="/import" routerLinkActive="active">Import</a>
          <a routerLink="/config" routerLinkActive="active">Trigger settings</a>
        </nav>

        <div class="account">
          <!-- Which business's data is on screen is a fact about every number
               here, not a caption under the product name. It gets a label and a
               border so it reads as context, and it sits next to sign-out
               because that is the control that changes it. -->
          @if (auth.businessName(); as business) {
            <span class="tenant" [title]="business">
              <span class="tenant-label">Viewing</span>
              <span class="tenant-name">{{ business }}</span>
            </span>
          }
          <button type="button" class="btn-secondary" (click)="auth.logout()">Sign out</button>
        </div>
      </div>
    </header>

    <main class="content">
      <router-outlet />
    </main>

    <app-toast-host />
  `,
  styles: [`
    .top {
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      position: sticky;
      top: 0;
      z-index: 10;
    }
    .bar {
      max-width: 1180px;
      margin: 0 auto;
      padding: var(--space-3) var(--space-4);
      display: flex;
      align-items: center;
      gap: var(--space-5);
      flex-wrap: wrap;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      text-decoration: none;
      color: var(--text);
      border-radius: var(--radius-sm);
    }
    .brand:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--focus-ring); }
    .mark { flex: none; display: block; }
    .mark .stop-from { stop-color: var(--primary-500); }
    .mark .stop-to { stop-color: var(--primary-700); }

    nav { display: flex; gap: var(--space-1); margin-right: auto; }
    nav a {
      padding: 7px 12px;
      border-radius: var(--radius-sm);
      text-decoration: none;
      color: var(--text-muted);
      font-weight: 500;
      font-size: var(--text-base);
      white-space: nowrap;
      transition: background-color var(--transition), color var(--transition);
    }
    nav a:hover { background: var(--neutral-100); color: var(--text); }
    /* Current location is carried by weight and a tint, not colour alone. */
    nav a.active { background: var(--primary-50); color: var(--primary-600); font-weight: 600; }

    .account { display: flex; align-items: center; gap: var(--space-3); }

    .tenant {
      display: flex;
      align-items: baseline;
      gap: var(--space-2);
      max-width: 260px;
      padding: 5px var(--space-3);
      border: 1px solid var(--border);
      border-radius: var(--radius-full);
      background: var(--surface-sunken);
    }
    .tenant-label {
      flex: none;
      font-size: var(--text-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-subtle);
    }
    /* Truncates rather than wraps: a long trading name must not be allowed to
       grow the header and push the nav onto a second row. */
    .tenant-name {
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--text);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .content {
      max-width: 1180px;
      margin: 0 auto;
      padding: var(--space-5) var(--space-4) var(--space-6);
    }

    /* Tablet: the four nav entries no longer fit beside the brand and account
       block, so they drop to their own full-width row instead of wrapping into
       a ragged one. The nav scrolls sideways rather than stacking, which would
       cost most of the viewport height. */
    @media (max-width: 900px) {
      .bar { gap: var(--space-3); }
      .brand { margin-right: auto; }
      nav {
        order: 3;
        width: 100%;
        margin-right: 0;
        overflow-x: auto;
        scrollbar-width: none;
      }
      nav::-webkit-scrollbar { display: none; }
      .tenant { max-width: 180px; }
    }

    @media (max-width: 560px) {
      .tenant-label { display: none; }
      .tenant { max-width: 140px; }
    }
  `],
})
export class ShellComponent {
  readonly auth = inject(AuthService);
}
