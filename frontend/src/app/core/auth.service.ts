import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse } from './api.models';

const TOKEN_KEY = 'lucklotter.token';
const BUSINESS_KEY = 'lucklotter.business';
const MUST_CHANGE_KEY = 'lucklotter.mustChangePassword';

/**
 * Holds the admin session (FR-6).
 *
 * The token lives in `localStorage` so a page refresh doesn't log the admin
 * out. That trades a little XSS exposure for usability — acceptable while the
 * app renders no user-supplied HTML, and the reason the API stays cookie-free
 * and CSRF-exempt.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly token = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  readonly businessName = signal<string | null>(localStorage.getItem(BUSINESS_KEY));
  readonly isLoggedIn = computed(() => this.token() !== null);
  /** True while the account is on a temporary password and can do nothing else. */
  readonly mustChangePassword = signal(localStorage.getItem(MUST_CHANGE_KEY) === 'true');

  constructor(private readonly http: HttpClient, private readonly router: Router) {}

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/v1/auth/login', { email, password })
      .pipe(tap((response) => this.store(response)));
  }

  /** Returns a fresh session: the old token stops working the moment this succeeds. */
  changePassword(currentPassword: string, newPassword: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/v1/auth/change-password', { currentPassword, newPassword })
      .pipe(tap((response) => this.store(response)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(BUSINESS_KEY);
    localStorage.removeItem(MUST_CHANGE_KEY);
    this.token.set(null);
    this.businessName.set(null);
    this.mustChangePassword.set(false);
    this.router.navigate(['/login']);
  }

  currentToken(): string | null {
    return this.token();
  }

  private store(response: LoginResponse): void {
    localStorage.setItem(TOKEN_KEY, response.token);
    localStorage.setItem(BUSINESS_KEY, response.businessName);
    localStorage.setItem(MUST_CHANGE_KEY, String(response.mustChangePassword));
    this.token.set(response.token);
    this.businessName.set(response.businessName);
    this.mustChangePassword.set(response.mustChangePassword);
  }
}
