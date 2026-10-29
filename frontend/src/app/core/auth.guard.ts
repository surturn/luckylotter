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
  if (!auth.isLoggedIn()) {
    return router.createUrlTree(['/login']);
  }
  // A temporary-password account is refused by the API everywhere else, so
  // send it to the one screen that works rather than to a page of errors.
  return auth.mustChangePassword() ? router.createUrlTree(['/change-password']) : true;
};

/** For the change-password screen itself: signed in, but not required to be past it. */
export const signedInGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isLoggedIn() ? true : inject(Router).createUrlTree(['/login']);
};
