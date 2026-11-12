import { Injectable, signal } from '@angular/core';

/**
 * Whether the local deploy agent answered, as last reported by the fleet/deployment page.
 *
 * <p>Tri-state on purpose: `null` is "not known" (no fleet page open, or its probe has not
 * settled), and only an explicit `false` means "away". The sidebar must render nothing for
 * `null`. The page makes the one real probe; this service only holds what it was told.
 */
@Injectable({ providedIn: 'root' })
export class AgentStatusService {
  readonly agentReachable = signal<boolean | null>(null);

  /** Takes a message payload; ignores anything that is not exactly `{type:'fleet:agent', reachable:boolean}`. */
  handleMessage(data: unknown): void {
    if (typeof data !== 'object' || data === null) return;
    const m = data as { type?: unknown; reachable?: unknown };
    if (m.type === 'fleet:agent' && typeof m.reachable === 'boolean') {
      this.agentReachable.set(m.reachable);
    }
  }

  /** The answer belongs to the page that gave it; once that page is gone it is not known again. */
  clear(): void {
    this.agentReachable.set(null);
  }
}
