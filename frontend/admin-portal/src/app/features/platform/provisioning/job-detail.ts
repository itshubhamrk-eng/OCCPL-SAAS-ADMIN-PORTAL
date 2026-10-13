import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ProvisioningJob } from '../../../core/api/models';
import { PlatformApi } from '../../../core/api/platform.api';
import { SessionService } from '../../../core/session/session.service';
import { ActionDialog } from '../../../shared/ui/action-dialog';
import { HumanizePipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-job-detail',
  imports: [RouterLink, PageHeader, StatusBadge, ActionDialog, DatePipe, HumanizePipe],
  template: `
    @if (job(); as j) {
      <occpl-page-header
        [heading]="(j.operation | humanize) + ' ' + (j.productCode ?? '')"
        [subtitle]="j.companyCode"
        backLink="/platform/provisioning"
        backLabel="Provisioning"
      >
        <occpl-status-badge [status]="j.status" />
        @if (session.hasPlatform('platform.provisioning.manage')) {
          @if (j.status === 'FAILED') {
            <button type="button" class="btn primary" (click)="retry()">Retry</button>
          }
          @if (j.status === 'PENDING' || j.status === 'AWAITING_CALLBACK') {
            <button type="button" class="btn danger" (click)="cancelDialog.open()">
              Cancel job
            </button>
          }
        }
      </occpl-page-header>
      <occpl-action-dialog
        #cancelDialog
        heading="Cancel provisioning job"
        confirmLabel="Cancel job"
        [danger]="true"
        reason="optional"
        message="The product will not be called for this job."
        (confirmed)="cancel($event)"
      />
      <section class="card">
        <dl class="details">
          <dt>Company</dt>
          <dd>
            <a [routerLink]="['/companies', j.companyId]">{{ j.companyCode ?? j.companyId }}</a>
          </dd>
          <dt>Product</dt>
          <dd>{{ j.productCode }}</dd>
          <dt>Attempts</dt>
          <dd>{{ j.attempts }} of {{ j.maxAttempts }}</dd>
          <dt>Next attempt</dt>
          <dd>{{ (j.nextAttemptAt | date: 'medium') ?? '—' }}</dd>
          <dt>Callback deadline</dt>
          <dd>{{ (j.callbackDeadline | date: 'medium') ?? '—' }}</dd>
          <dt>Configuration</dt>
          <dd>{{ j.configVersionNo ? 'Version ' + j.configVersionNo : '—' }}</dd>
          <dt>Last HTTP status</dt>
          <dd>{{ j.lastHttpStatus ?? '—' }}</dd>
          <dt>Last error</dt>
          <dd>{{ j.lastError ?? '—' }}</dd>
          <dt>Created</dt>
          <dd>{{ j.createdAt | date: 'medium' }}</dd>
          <dt>Completed</dt>
          <dd>{{ (j.completedAt | date: 'medium') ?? '—' }}</dd>
          <dt>Job ID</dt>
          <dd class="mono">{{ j.id }}</dd>
        </dl>
      </section>
    } @else {
      <p role="status">Loading…</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JobDetail implements OnInit {
  private readonly api = inject(PlatformApi);
  protected readonly session = inject(SessionService);
  readonly jobId = input.required<string>();
  protected readonly job = signal<ProvisioningJob | null>(null);

  async ngOnInit(): Promise<void> {
    this.job.set(await firstValueFrom(this.api.job(this.jobId())));
  }

  protected async retry(): Promise<void> {
    this.job.set(await firstValueFrom(this.api.retryJob(this.jobId())));
  }

  protected async cancel(reason: string | null): Promise<void> {
    this.job.set(await firstValueFrom(this.api.cancelJob(this.jobId(), reason)));
  }
}
