import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Keeps unauthenticated users off the dashboard routes.
 *
 * Convenience only — it prevents a pointless render, not access. The API
 * rejects every unauthenticated call regardless (NFR-1), which is what actually
 * protects the data.
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isLoggedIn() ? true : router.createUrlTree(['/login']);
};
