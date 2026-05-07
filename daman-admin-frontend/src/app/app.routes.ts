import { Routes } from '@angular/router';
import { ClientsComponent } from './pages/clients/clients.component';
import { ClientFormComponent } from './pages/client-form/client-form.component';
import { LicensesComponent } from './pages/licenses/licenses.component';
import { VersionsComponent } from './pages/versions/versions.component';

export const routes: Routes = [
  { path: '', redirectTo: '/clients', pathMatch: 'full' },
  { path: 'clients', component: ClientsComponent },
  { path: 'clients/new', component: ClientFormComponent },
  { path: 'clients/:clientCode/edit', component: ClientFormComponent },
  { path: 'licenses', component: LicensesComponent },
  { path: 'versions', component: VersionsComponent }
];
