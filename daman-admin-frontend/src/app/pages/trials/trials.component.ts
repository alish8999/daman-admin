import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TrialService, TrialRegistration } from '../../services/trial.service';
import { TranslatePipe } from '../../pipes/translate.pipe';

@Component({
  selector: 'app-trials',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './trials.component.html',
  styleUrl: './trials.component.css'
})
export class TrialsComponent implements OnInit {
  trials: TrialRegistration[] = [];
  loading = true;
  error = false;

  constructor(private trialService: TrialService) {}

  ngOnInit(): void {
    this.trialService.getAll().subscribe({
      next: rows => { this.trials = rows; this.loading = false; },
      error: () => { this.error = true; this.loading = false; }
    });
  }

  count(state: string): number {
    return this.trials.filter(t => t.state === state).length;
  }
}
