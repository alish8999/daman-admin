import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ClientService, DevRunResult, DevCurrent } from '../../services/client.service';
import { TranslationService } from '../../services/translation.service';
import { TranslatePipe } from '../../pipes/translate.pipe';

/**
 * Self-contained "Run as this client" control (local dev workflow — see
 * DevRunService, SECURITY note there). Renders a trigger button, a "running"
 * pill + "Reset dev" when this client is the active checkout, and the run
 * dialog. Fetches its own {@link DevCurrent} so it can be dropped anywhere a
 * clientCode is in hand (clients list row, client-form header).
 */
@Component({
  selector: 'app-dev-run-button',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe],
  templateUrl: './dev-run-button.component.html',
  styles: [`
    .modal-backdrop-custom { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 1050; }
    .modal-dialog-custom {
      position: fixed; top: 50%; left: 50%; transform: translate(-50%,-50%);
      z-index: 1060; width: min(560px, 94vw); max-height: 88vh; overflow-y: auto;
      background: #fff; border-radius: .5rem; box-shadow: 0 10px 40px rgba(0,0,0,.25);
    }
    .modal-dialog-custom code { word-break: break-all; }
  `]
})
export class DevRunButtonComponent implements OnInit {
  @Input({ required: true }) clientCode!: string;
  /** Default the dialog to "generic" mode when the client has an ACTIVE v2 licence. */
  @Input() hasActiveV2License = false;
  /** Small button for dense table rows; normal size otherwise. */
  @Input() compact = false;
  /** Show the "running" pill + "Reset dev" alongside the trigger. Turn off where
   *  the host already renders its own dev-run status (e.g. the clients list). */
  @Input() showStatus = true;
  /** Fires after any state change (run / reset) so a parent can refresh its own view. */
  @Output() changed = new EventEmitter<void>();

  devCurrent: DevCurrent | null = null;

  dialogOpen = false;
  mode: 'per-client' | 'generic' = 'per-client';
  dbFile = '';
  busy = false;
  error = '';
  needsMachineId = false;
  machineId = '';
  result: DevRunResult | null = null;

  constructor(
    private clientService: ClientService,
    public translationService: TranslationService
  ) {}

  ngOnInit(): void {
    this.loadDevCurrent();
  }

  get isActive(): boolean {
    return this.devCurrent?.clientCode === this.clientCode;
  }

  loadDevCurrent(): void {
    this.clientService.devCurrent().subscribe({
      next: c => this.devCurrent = c,
      error: () => this.devCurrent = null
    });
  }

  open(): void {
    this.mode = this.hasActiveV2License ? 'generic' : 'per-client';
    this.dbFile = '';
    this.error = '';
    this.needsMachineId = false;
    this.machineId = '';
    this.result = null;
    this.dialogOpen = true;
  }

  close(): void {
    if (!this.busy) this.dialogOpen = false;
  }

  submit(): void {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.clientService.devRun(this.clientCode, {
      mode: this.mode,
      dbFile: this.dbFile.trim() || undefined
    }).subscribe({
      next: res => {
        this.busy = false;
        this.result = res;
        this.loadDevCurrent();
        this.changed.emit();
      },
      error: err => {
        this.busy = false;
        const msg = err.error?.error || 'Dev run failed.';
        this.error = msg;
        this.needsMachineId = /machine ID unknown/i.test(msg);
      }
    });
  }

  saveMachineIdAndRetry(): void {
    const id = this.machineId.trim();
    if (!id) return;
    this.clientService.setDevMachineId(id).subscribe({
      next: () => { this.needsMachineId = false; this.submit(); },
      error: err => this.error = err.error?.error || 'Could not save the machine ID.'
    });
  }

  reset(): void {
    if (!confirm(this.translationService.instant('devResetConfirm'))) return;
    this.clientService.devReset().subscribe({
      next: () => { this.loadDevCurrent(); this.changed.emit(); },
      error: err => alert(err.error?.error || 'Reset failed.')
    });
  }
}
