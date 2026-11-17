import { License } from '../services/license.service';

/**
 * What a licence row actually is — because three of the four kinds are not a branch, and
 * counting them as one makes the fleet look far bigger than it is.
 *
 * Measured on the live fleet 2026-11-18, of 105 licences: 29 sit on ONE machine id (this
 * development PC, from "Run as this client"), 8 sit on another single machine id (the hosted
 * server — every hosted store shares it), and 4 are REVOKED, which hosted-store re-provisioning
 * produces routinely. None is hidden; the owner needs to see them. They are just not branches.
 */
export type LicenceKind = 'shop' | 'dev' | 'hosted' | 'revoked';

/** The label hosted-store provisioning writes on every licence it mints. */
const HOSTED_LABEL = 'hosted-store';

/**
 * Machine ids that serve MORE THAN ONE client.
 *
 * <p>This is the signal, and it is structural rather than a guess: a real till belongs to one
 * business, so a machine id appearing under several client codes is a shared box — the
 * development PC or the hosted server — never a branch.
 *
 * <p>It replaced asking the backend for the dev machine id, which cannot work: that comes from
 * `dev-current`, which reads `client-meta.properties` out of the dev CHECKOUT, so on the hosted
 * portal — where this page is actually used — it is always null. Labels are no good either: of
 * the 29 dev rows, several (`c-16`, `decoration`, `minimart_ali`, `muraba3`) carry no label at
 * all.
 */
export function sharedMachineIds(licences: License[]): Set<string> {
  const clientsPerMachine = new Map<string, Set<string>>();
  for (const l of licences) {
    if (!l.machineId) continue;
    const set = clientsPerMachine.get(l.machineId) ?? new Set<string>();
    set.add(l.clientCode);
    clientsPerMachine.set(l.machineId, set);
  }
  const shared = new Set<string>();
  clientsPerMachine.forEach((clients, machineId) => {
    if (clients.size > 1) shared.add(machineId);
  });
  return shared;
}

/**
 * Status first: a revoked licence is revoked, whatever machine it sits on. Then the hosted
 * label, then the shared-machine test. A caller with no `shared` set gets `shop` for everything
 * that is active and unlabelled — a missing badge, never a wrong one.
 */
export function licenceKind(licence: License, shared?: Set<string> | null): LicenceKind {
  if ((licence.status ?? '').trim().toUpperCase() !== 'ACTIVE') return 'revoked';
  if ((licence.label ?? '').trim() === HOSTED_LABEL) return 'hosted';
  if (shared && licence.machineId && shared.has(licence.machineId)) return 'dev';
  return 'shop';
}

/**
 * Whether this licence is one a shop could run on today: ACTIVE **and** not past its expiry.
 *
 * <p>Mirrors the backend's `LicenceLiveness`, because `status === 'ACTIVE'` is a different
 * question: 43 of the live fleet's 101 ACTIVE licences expired already, and those installs are
 * locked. The server sends `live` on each DTO; the date arithmetic is the fallback for a payload
 * that predates that field.
 */
export function isLicenceLive(licence: License, today = new Date()): boolean {
  if (typeof licence.live === 'boolean') return licence.live;
  if ((licence.status ?? '').trim().toUpperCase() !== 'ACTIVE') return false;
  if (!licence.expiresAt) return true;
  return new Date(licence.expiresAt + 'T00:00:00').getTime() >= new Date(today).setHours(0, 0, 0, 0);
}
