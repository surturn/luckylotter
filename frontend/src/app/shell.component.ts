import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

/** Frame around the authenticated views: brand, nav, and sign-out. */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="top">
      <div class="bar">
        <div class="brand">
          <span class="mark" aria-hidden="true"></span>
          <div class="titles">
            <strong>LuckLotter</strong>
            <span class="muted small">{{ auth.businessName() }}</span>
          </div>
        </div>

        <nav aria-label="Main">
          <a routerLink="/flags" routerLinkActive="active">Flagged customers</a>
          <a routerLink="/config" routerLinkActive="active">Trigger settings</a>
        </nav>

        <button type="button" class="btn-secondary" (click)="auth.logout()">Sign out</button>
      </div>
    </header>

    <main class="content">
      <router-outlet />
    </main>
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
    .brand { display: flex; align-items: center; gap: var(--space-3); }
    .titles { display: flex; flex-direction: column; line-height: 1.25; }
    .mark {
      width: 30px; height: 30px; border-radius: 8px; flex: none;
      background: linear-gradient(140deg, var(--primary-600), var(--warning-600));
    }
    nav { display: flex; gap: var(--space-2); margin-right: auto; }
    nav a {
      padding: 7px 12px;
      border-radius: var(--radius-sm);
      text-decoration: none;
      color: var(--text-muted);
      font-weight: 500;
      font-size: var(--text-base);
      transition: background-color var(--transition), color var(--transition);
    }
    nav a:hover { background: var(--neutral-100); color: var(--text); }
    /* Current location is carried by weight and a tint, not colour alone. */
    nav a.active { background: var(--primary-50); color: var(--primary-600); font-weight: 600; }
    .content {
      max-width: 1180px;
      margin: 0 auto;
      padding: var(--space-5) var(--space-4) var(--space-6);
    }
  `],
})
export class ShellComponent {
  readonly auth = inject(AuthService);
}
