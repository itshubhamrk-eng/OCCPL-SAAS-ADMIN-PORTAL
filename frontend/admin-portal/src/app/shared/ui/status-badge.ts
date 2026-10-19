import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { humanize } from './format';

const TONES: Record<string, 'success' | 'warning' | 'danger' | 'info' | 'neutral'> = {
  ACTIVE: 'success',
  PROVISIONED: 'success',
  SUCCEEDED: 'success',
  ACCEPTED: 'success',
  PUBLISHED: 'success',
  SUCCESS: 'success',
  TRIAL: 'info',
  NEW: 'info',
  CONTACTED: 'info',
  QUALIFIED: 'warning',
  CONVERTED: 'success',
  LOST: 'neutral',
  PENDING: 'info',
  INVITED: 'info',
  DRAFT: 'info',
  RUNNING: 'info',
  PROVISIONING: 'info',
  AWAITING_CALLBACK: 'info',
  GRACE: 'warning',
  PAST_DUE: 'warning',
  SUSPENDED: 'warning',
  DEPROVISION_SCHEDULED: 'warning',
  DEPROVISIONING: 'warning',
  DEPRECATED: 'warning',
  FAILED: 'danger',
  FAILURE: 'danger',
  DISABLED: 'danger',
  DEACTIVATED: 'neutral',
  CANCELLED: 'neutral',
  EXPIRED: 'neutral',
  REVOKED: 'neutral',
  RETIRED: 'neutral',
  ARCHIVED: 'neutral',
  DEPROVISIONED: 'neutral',
};

@Component({
  selector: 'occpl-status-badge',
  template: `<span class="badge" [class]="'badge ' + tone()">{{ label() }}</span>`,
  styles: `
    .badge {
      display: inline-block;
      padding: 0.125rem 0.5rem;
      border-radius: 999px;
      font-size: var(--occpl-font-size-sm);
      font-weight: 600;
      white-space: nowrap;
      border: 1px solid transparent;
    }
    .success {
      background: var(--occpl-color-success-surface);
      color: var(--occpl-color-success);
    }
    .warning {
      background: var(--occpl-color-warning-surface);
      color: var(--occpl-color-warning);
    }
    .danger {
      background: var(--occpl-color-danger-surface);
      color: var(--occpl-color-danger);
    }
    .info {
      background: var(--occpl-color-info-surface);
      color: var(--occpl-color-info);
    }
    .neutral {
      background: var(--occpl-color-neutral-surface);
      color: var(--occpl-color-text-muted);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusBadge {
  readonly status = input.required<string | null | undefined>();
  protected readonly tone = computed(() => TONES[this.status() ?? ''] ?? 'neutral');
  protected readonly label = computed(() => humanize(this.status()));
}
