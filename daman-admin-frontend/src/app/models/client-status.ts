import { License } from '../services/license.service';
import { Billing } from './billing.model';
import { ClientConfig } from './client-config.model';

export type ComputedStatus = 'ACTIVE' | 'TRIAL' | 'DUMMY';

export interface ClientStatusResult {
  status: ComputedStatus;
  /** True only when status === 'ACTIVE' but the relevant license is currently expired or revoked. */
  lapsed: boolean;
}

function isLicenseValid(license: License | undefined): boolean {
  if (!license) return false;
  if (license.status !== 'ACTIVE') return false;
  if (!license.expiresAt) return true;
  return new Date(license.expiresAt + 'T00:00:00').getTime() >= new Date().setHours(0, 0, 0, 0);
}

/**
 * Client status is no longer a manually-set field — it's derived from whether the
 * client has a license at all, and whether they've ever paid for it:
 *   DUMMY  — no license (nothing set up yet)
 *   TRIAL  — has a license but no PAID billing record
 *   ACTIVE — has a license and a PAID billing record (flagged `lapsed` if the
 *            license that billing paid for — or, absent a link, the client's most
 *            recently activated license — is currently expired or revoked)
 */
export function computeClientStatus(
  client: Pick<ClientConfig, 'clientCode'>,
  licenses: License[],
  billings: Billing[]
): ClientStatusResult {
  const clientLicenses = licenses.filter(l => l.clientCode === client.clientCode);
  if (clientLicenses.length === 0) {
    return { status: 'DUMMY', lapsed: false };
  }

  const clientBillings = billings
    .filter(b => b.clientCode === client.clientCode)
    .sort((a, b) => new Date(b.paymentDate ?? b.createdAt).getTime() - new Date(a.paymentDate ?? a.createdAt).getTime());

  const latestPaid = clientBillings.find(b => b.paymentStatus === 'PAID');
  if (!latestPaid) {
    return { status: 'TRIAL', lapsed: false };
  }

  const mostRecentLicense = [...clientLicenses].sort(
    (a, b) => new Date(b.activatedAt).getTime() - new Date(a.activatedAt).getTime()
  )[0];
  const relevantLicense = latestPaid.licenseId
    ? (clientLicenses.find(l => l.id === latestPaid.licenseId) ?? mostRecentLicense)
    : mostRecentLicense;

  return { status: 'ACTIVE', lapsed: !isLicenseValid(relevantLicense) };
}
