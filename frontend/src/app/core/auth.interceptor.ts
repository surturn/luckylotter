import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the JWT to API calls and treats a 401 as "the session is over".
 *
 * A token expires server-side while the tab stays open, so without this an
 * admin would sit on a dashboard silently failing to refresh. The login call
 * itself is exempt — a wrong password there is a form error, not a dead
 * session.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const token = auth.currentToken();
  const isLoginRequest = request.url.endsWith('/v1/auth/login');

  const authorized = token && !isLoginRequest
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !isLoginRequest) {
        auth.logout();
      }
      return throwError(() => error);
    })
  );
};
