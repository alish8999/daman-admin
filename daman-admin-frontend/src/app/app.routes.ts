import { Routes } from '@angular/router';
import { ClientsComponent } from './pages/clients/clients.component';
import { ClientFormComponent } from './pages/client-form/client-form.component';
import { VersionsComponent } from './pages/versions/versions.component';
import { TrialsComponent } from './pages/trials/trials.component';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { LoginComponent } from './pages/login/login.component';
import { authGuard } from './guards/auth.guard';
import { FleetComponent } from './pages/fleet/fleet.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'clients', component: ClientsComponent, canActivate: [authGuard] },
  { path: 'clients/new', component: ClientFormComponent, canActivate: [authGuard] },
  { path: 'clients/:clientCode/edit', component: ClientFormComponent, canActivate: [authGuard] },
  { path: 'versions', component: VersionsComponent, canActivate: [authGuard] },
  { path: 'trials', component: TrialsComponent, canActivate: [authGuard] },
  // The fleet page is static assets; authGuard cannot protect a bare file, so it is mounted
  // behind this component instead. See FleetComponent.
  { path: 'fleet', component: FleetComponent, canActivate: [authGuard] }
];
