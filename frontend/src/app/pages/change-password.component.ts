import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';

const MIN_LENGTH = 12;

/** The two new-password fields must agree. Reported on the confirm field. */
function matching(group: AbstractControl): ValidationErrors | null {
  const next = group.get('newPassword')?.value;
  const confirm = group.get('confirm')?.value;
  return next && confirm && next !== confirm ? { mismatch: true } : null;
}

/**
 * Change password. Standalone, outside the shell, because it is also the one
 * screen a temporary-password account is allowed to see: until it is done the
 * API refuses everything else, so the dashboard around it would be a frame
 * full of errors.
 */
@Component({
  selector: 'app-change-password',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="wrap">
      <div class="card panel">
        <h1>{{ auth.mustChangePassword() ? 'Choose a new password' : 'Change password' }}</h1>
        @if (auth.mustChangePassword()) {
          <p class="muted">
            You signed in with a temporary password. Choose your own before you continue.
          </p>
        }

        @if (error()) {
          <div class="banner banner-error" role="alert">{{ error() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="field">
            <label for="current">{{ auth.mustChangePassword() ? 'Temporary password' : 'Current password' }}</label>
            <input id="current" type="password" formControlName="currentPassword"
                   autocomplete="current-password" [class.invalid]="invalid('currentPassword')" />
            @if (invalid('currentPassword')) {
              <p class="error-text">Enter your current password.</p>
            }
          </div>

          <div class="field">
            <label for="new">New password</label>
            <input id="new" type="password" formControlName="newPassword"
                   autocomplete="new-password" [class.invalid]="invalid('newPassword')" />
            <p class="muted small">At least {{ minLength }} characters. A few random words works well.</p>
            @if (invalid('newPassword')) {
              <p class="error-text">Use at least {{ minLength }} characters.</p>
            }
          </div>

          <div class="field">
            <label for="confirm">Confirm new password</label>
            <input id="confirm" type="password" formControlName="confirm"
                   autocomplete="new-password" [class.invalid]="mismatch()" />
            @if (mismatch()) {
              <p class="error-text">The two passwords don't match.</p>
            }
          </div>

          <button type="submit" class="btn-primary" style="width:100%" [disabled]="busy()">
            {{ busy() ? 'Saving…' : 'Save new password' }}
          </button>
        </form>

        @if (auth.mustChangePassword()) {
          <p class="small"><button type="button" class="link" (click)="auth.logout()">Sign out instead</button></p>
        } @else {
          <p class="small"><a routerLink="/overview">Back to the dashboard</a></p>
        }
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
      max-width: 400px;
      padding: var(--space-6);
      box-shadow: var(--shadow-md);
    }
    h1 { margin: 0 0 var(--space-2); }
    .link {
      background: none;
      border: 0;
      padding: 0;
      color: var(--primary-600, inherit);
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
      min-height: 0;
      display: inline;
    }
  `],
})
export class ChangePasswordComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly minLength = MIN_LENGTH;
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly form = inject(FormBuilder).nonNullable.group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(MIN_LENGTH)]],
      confirm: ['', Validators.required],
    },
    { validators: matching }
  );

  invalid(control: 'currentPassword' | 'newPassword'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  mismatch(): boolean {
    return this.form.hasError('mismatch') && this.form.controls.confirm.touched;
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const { currentPassword, newPassword } = this.form.getRawValue();
    this.auth.changePassword(currentPassword, newPassword).subscribe({
      next: () => this.router.navigate(['/overview']),
      error: (response) => {
        this.busy.set(false);
        // The server's 400 message is written for this form ("Current password
        // is incorrect"); anything else is a connectivity problem.
        this.error.set(
          response.status === 400 && response.error?.message
            ? response.error.message
            : 'Could not reach the server. Check your connection and try again.'
        );
      },
    });
  }
}
