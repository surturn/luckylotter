import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { ShellComponent } from './shell.component';

/**
 * Dashboard routes sit behind the shell and the auth guard; login sits outside
 * both. Every view is lazily loaded, so the login screen doesn't ship the
 * dashboard's code to someone who hasn't signed in yet.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'overview',
        loadComponent: () => import('./pages/overview.component').then((m) => m.OverviewComponent),
      },
      {
        path: 'flags',
        loadComponent: () => import('./pages/flags-list.component').then((m) => m.FlagsListComponent),
      },
      {
        path: 'flags/:id',
        loadComponent: () => import('./pages/flag-detail.component').then((m) => m.FlagDetailComponent),
      },
      {
        path: 'config',
        loadComponent: () => import('./pages/config.component').then((m) => m.ConfigComponent),
      },
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
    ],
  },
  { path: '**', redirectTo: '' },
];
