import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { ToastHostComponent } from './shared/toast-host.component';

/**
 * Frame around the authenticated views: brand, nav, tenant context, sign-out.
 *
 * <b>Sidebar, decided 2026-08-13.</b> This reverses the earlier "top nav, not a
 * sidebar" call. The old reasoning — four destinations don't earn a permanent
 * column, and the flag table wants the width — still describes a real cost, and
 * it is paid here: the content area is ~240px narrower on desktop. What changed
 * is the judgement, on a design direction: a persistent left rail reads as an
 * operations tool rather than a settings page, and it gives the tenant identity
 * a fixed home at the bottom instead of competing with navigation across the
 * top.
 *
 * The mockups showed both a sidebar *and* a top nav carrying the same four
 * destinations. That is not implemented — duplicated navigation doubles the
 * places a user has to look without adding a destination, and the second copy
 * would take back the vertical space the sidebar just spent. The top strip
 * keeps only what is about the session rather than the location: which business
 * is on screen, and how to leave.
 *
 * Below 900px the rail becomes a horizontal scroller so it costs height rather
 * than half the viewport.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHostComponent],
  template: `
    <div class="layout">
      <aside class="rail">
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
          <a routerLink="/overview" routerLinkActive="active">
            <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="2.5" width="6" height="6" rx="1.5"/><rect x="11.5" y="2.5" width="6" height="6" rx="1.5"/><rect x="2.5" y="11.5" width="6" height="6" rx="1.5"/><rect x="11.5" y="11.5" width="6" height="6" rx="1.5"/></svg>
            Overview
          </a>
          <a routerLink="/flags" routerLinkActive="active">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4.5 17.5V3.5h9l-1.5 3 1.5 3h-9"/></svg>
            Flagged customers
          </a>
          <a routerLink="/import" routerLinkActive="active">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5M3.5 14v2.5h13V14"/></svg>
            Import
          </a>
          <a routerLink="/config" routerLinkActive="active">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 6h14M3 14h14"/><circle cx="8" cy="6" r="2.25"/><circle cx="13" cy="14" r="2.25"/></svg>
            Trigger settings
          </a>
        </nav>

        <!-- Which business's data is on screen is a fact about every number in
             the app, so it sits at the foot of the rail where it is always
             visible, rather than scrolling away with the page. -->
        @if (auth.businessName(); as business) {
          <div class="tenant" [title]="business">
            <span class="tenant-initial" aria-hidden="true">{{ business.charAt(0) }}</span>
            <span class="tenant-text">
              <span class="tenant-name">{{ business }}</span>
              <span class="tenant-label">Viewing account</span>
            </span>
          </div>
        }
      </aside>

      <div class="pane">
        <header class="top">
          <!-- Session, not location: the rail says where you are, this says
               whose data you are looking at and how to stop. -->
          @if (auth.businessName(); as business) {
            <span class="top-tenant" [title]="business">
              <span class="top-tenant-label">Viewing</span>
              <span class="top-tenant-name">{{ business }}</span>
            </span>
          }
          <button type="button" class="btn-secondary" (click)="auth.logout()">Sign out</button>
        </header>

        <main class="content">
          <router-outlet />
        </main>
      </div>
    </div>

    <app-toast-host />
  `,
  styles: [`
    .layout {
      display: grid;
      grid-template-columns: 240px minmax(0, 1fr);
      min-height: 100vh;
    }
    .rail {
      display: flex;
      flex-direction: column;
      gap: var(--space-5);
      padding: var(--space-5) var(--space-4);
      background: var(--surface);
      border-right: 1px solid var(--border);
      position: sticky;
      top: 0;
      height: 100vh;
    }
    .pane { min-width: 0; display: flex; flex-direction: column; }
    .top {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-6);
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      position: sticky;
      top: 0;
      z-index: 10;
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

    nav { display: flex; flex-direction: column; gap: 2px; margin-bottom: auto; }
    nav a {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: 9px var(--space-3);
      border-radius: var(--radius-sm);
      text-decoration: none;
      color: var(--text-muted);
      font-weight: 500;
      font-size: var(--text-base);
      white-space: nowrap;
      transition: background-color var(--transition), color var(--transition);
    }
    nav a svg {
      flex: none;
      width: 20px;
      height: 20px;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.5;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    nav a:hover { background: var(--neutral-100); color: var(--text); }
    nav a:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--focus-ring); }
    /* Current location is carried by weight and a tint, not colour alone. */
    nav a.active { background: var(--primary-50); color: var(--primary-600); font-weight: 600; }

    .tenant {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border);
      min-width: 0;
    }
    .tenant-initial {
      flex: none;
      width: 32px;
      height: 32px;
      display: grid;
      place-items: center;
      border-radius: var(--radius-full);
      background: var(--primary-50);
      color: var(--primary-600);
      font-weight: 700;
      font-size: var(--text-sm);
    }
    .tenant-text { display: flex; flex-direction: column; min-width: 0; }
    /* Truncates rather than wraps: a long trading name must not be allowed to
       widen the rail or push the account block out of view. */
    .tenant-name {
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--text);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .tenant-label { font-size: var(--text-xs); color: var(--text-subtle); }

    .top-tenant {
      display: flex;
      align-items: baseline;
      gap: var(--space-2);
      max-width: 260px;
      padding: 5px var(--space-3);
      border: 1px solid var(--border);
      border-radius: var(--radius-full);
      background: var(--surface-sunken);
    }
    .top-tenant-label {
      flex: none;
      font-size: var(--text-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-subtle);
    }
    .top-tenant-name {
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--text);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .content {
      max-width: 1240px;
      width: 100%;
      margin: 0 auto;
      padding: var(--space-6) var(--space-6) var(--space-8);
    }

    /* Tablet and below: a fixed rail would take half the viewport, so it
       becomes a horizontal strip that costs height instead of width. The nav
       scrolls sideways rather than stacking, for the same reason. */
    @media (max-width: 900px) {
      .layout { grid-template-columns: minmax(0, 1fr); }
      .rail {
        position: static;
        height: auto;
        flex-direction: row;
        align-items: center;
        gap: var(--space-4);
        border-right: none;
        border-bottom: 1px solid var(--border);
        padding: var(--space-3) var(--space-4);
      }
      nav {
        flex-direction: row;
        margin-bottom: 0;
        margin-right: auto;
        overflow-x: auto;
        scrollbar-width: none;
      }
      nav::-webkit-scrollbar { display: none; }
      nav a svg { display: none; }
      /* The rail's account block and the header's tenant chip say the same
         thing; at this width only one of them earns the room. */
      .tenant { display: none; }
      .content { padding: var(--space-5) var(--space-4) var(--space-6); }
    }

    @media (max-width: 560px) {
      .top-tenant-label { display: none; }
      .top-tenant { max-width: 140px; }
    }
  `],
})
export class ShellComponent {
  readonly auth = inject(AuthService);
}
