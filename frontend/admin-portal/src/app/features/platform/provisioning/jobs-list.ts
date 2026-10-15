import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { JobStatus, Page, ProvisioningJob } from '../../../core/api/models';
import { PlatformApi } from '../../../core/api/platform.api';
import { HumanizePipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-jobs-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge, DatePipe, HumanizePipe],
  template: `
    <occpl-page-header
      heading="Provisioning"
      subtitle="Setting companies up in product services, and removing them after the retention period"
    />
    <div class="filters">
      <div class="field">
        <label for="status">Status</label>
        <select id="status" [(ngModel)]="statusFilter" (ngModelChange)="search(0)">
          <option value="">All</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s | humanize }}</option>
          }
        </select>
      </div>
      <button type="button" class="btn" (click)="search(page()?.page ?? 0)">Refresh</button>
    </div>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Job</th>
            <th>Company</th>
            <th>Product</th>
            <th>Status</th>
            <th>Attempts</th>
            <th>Next attempt</th>
          </tr>
        </thead>
        <tbody>
          @for (j of page()?.items ?? []; track j.id) {
            <tr>
              <td data-label="Job">
                <a [routerLink]="[j.id]">{{ j.operation | humanize }}</a>
                <div class="small muted">{{ j.createdAt | date: 'medium' }}</div>
              </td>
              <td data-label="Company">
                <a [routerLink]="['/companies', j.companyId]">{{ j.companyCode ?? j.companyId }}</a>
              </td>
              <td data-label="Product">{{ j.productCode }}</td>
              <td data-label="Status"><occpl-status-badge [status]="j.status" /></td>
              <td data-label="Attempts">{{ j.attempts }} / {{ j.maxAttempts }}</td>
              <td data-label="Next attempt">{{ (j.nextAttemptAt | date: 'medium') ?? '—' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">{{ page() ? 'No jobs match.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobsList implements OnInit {
  private readonly api = inject(PlatformApi);
  readonly status = input<string | undefined>();
  protected readonly statuses: JobStatus[] = [
    'PENDING',
    'RUNNING',
    'AWAITING_CALLBACK',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
  ];
  protected readonly page = signal<Page<ProvisioningJob> | null>(null);
  protected statusFilter = '';

  ngOnInit(): void {
    this.statusFilter = this.status() ?? '';
    this.search(0);
  }

  protected search(page: number): void {
    this.api
      .jobs({ status: (this.statusFilter || null) as JobStatus | null, page, size: 20 })
      .subscribe((p) => this.page.set(p));
  }
}
