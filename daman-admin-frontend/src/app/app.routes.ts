import { Routes } from '@angular/router';
import { ClientsComponent } from './pages/clients/clients.component';
import { ClientFormComponent } from './pages/client-form/client-form.component';
import { VersionsComponent } from './pages/versions/versions.component';
import { TrialsComponent } from './pages/trials/trials.component';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { LoginComponent } from './pages/login/login.component';
import { authGuard } from './guards/auth.guard';
import { FleetComponent } from './pages/fleet/fleet.component';
import { LicenceDriftComponent } from './pages/licence-drift/licence-drift.component';
import { AnnouncementsComponent } from './pages/announcements/announcements.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'clients', component: ClientsComponent, canActivate: [authGuard] },
  { path: 'clients/new', component: ClientFormComponent, canActivate: [authGuard] },
  { path: 'clients/:clientCode/edit', component: ClientFormComponent, canActivate: [authGuard] },
  { path: 'versions', component: VersionsComponent, canActivate: [authGuard] },
  { path: 'trials', component: TrialsComponent, canActivate: [authGuard] },
  { path: 'licence-drift', component: LicenceDriftComponent, canActivate: [authGuard] },
  { path: 'announcements', component: AnnouncementsComponent, canActivate: [authGuard] },
  // The fleet page is static assets; authGuard cannot protect a bare file, so it is mounted
  // behind this component instead. See FleetComponent.
  // Every route here MUST be ONE flat segment (no '/', no trailing slash): the component iframes
  // the RELATIVE 'fleet-page/index.html', which under a nested route resolves beneath it, misses
  // the asset and renders the whole portal inside its own iframe. Pinned by
  // deploy-dashboard/lib/portal-route-flatness.test.js, whose regexes also fix the property
  // order: component, then data, then canActivate.
  { path: 'fleet', component: FleetComponent, data: { view: 'stores' }, canActivate: [authGuard] },
  { path: 'deployment', component: FleetComponent, data: { view: 'deploy' }, canActivate: [authGuard] }
];
