import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <main class="wrap">
      <div class="card panel">
        <div class="brand">
          <!-- The same mark the shell uses. This is the first screen anyone
               sees, so it can't be the placeholder the rest of the app grew
               out of. -->
          <svg class="mark" width="34" height="34" viewBox="0 0 28 28" fill="none" aria-hidden="true">
            <rect width="28" height="28" rx="8" fill="url(#luck-mark-login)" />
            <g stroke="#fff" stroke-width="2" stroke-linecap="round">
              <path d="M6 18v-4" opacity="0.55" />
              <path d="M10.5 18v-6" opacity="0.75" />
              <path d="M15 18v-8" />
              <path d="M22 8.5v11" stroke-dasharray="0.5 3.5" />
            </g>
            <defs>
              <linearGradient id="luck-mark-login" x1="0" y1="0" x2="28" y2="28">
                <stop class="stop-from" />
                <stop class="stop-to" offset="1" />
              </linearGradient>
            </defs>
          </svg>
          <div>
            <h1>LuckLotter</h1>
            <p class="muted small" style="margin:0">Retention dashboard</p>
          </div>
        </div>

        @if (error()) {
          <div class="banner banner-error" role="alert">{{ error() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="field">
            <label for="email">Work email</label>
            <input id="email" type="email" formControlName="email" autocomplete="username"
                   [class.invalid]="invalid('email')" />
            @if (invalid('email')) {
              <p class="error-text">Enter the email address your account was created with.</p>
            }
          </div>

          <div class="field">
            <label for="password">Password</label>
            <input id="password" type="password" formControlName="password" autocomplete="current-password"
                   [class.invalid]="invalid('password')" />
            @if (invalid('password')) {
              <p class="error-text">Enter your password.</p>
            }
          </div>

          <button type="submit" class="btn-primary" style="width:100%" [disabled]="busy()">
            {{ busy() ? 'Signing in…' : 'Sign in' }}
          </button>
        </form>
      </div>
    </main>
  `,
  styles: [`
    .wrap {
      min-height: 100dvh;
      display: grid;
      place-items: center;
      padding: var(--space-4);
    }
    .panel {
      width: 100%;
      max-width: 380px;
      padding: var(--space-6);
      box-shadow: var(--shadow-md);
    }
    .brand {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      margin-bottom: var(--space-5);
    }
    .mark { flex: none; }
    /* stop-color as a presentation attribute doesn't resolve var(), so the
       gradient stops are styled here — same reason as in the shell. */
    .mark .stop-from { stop-color: var(--primary-500); }
    .mark .stop-to { stop-color: var(--primary-700); }
  `],
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  /** Errors appear once the field has been left, not on every keystroke. */
  invalid(control: 'email' | 'password'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => this.router.navigate(['/flags']),
      error: (response) => {
        this.busy.set(false);
        // Says what to do next, not just that something was invalid.
        this.error.set(
          response.status === 401
            ? 'That email and password don\'t match an account. Check both and try again.'
            : 'Could not reach the server. Check your connection and try again.'
        );
      },
    });
  }
}
