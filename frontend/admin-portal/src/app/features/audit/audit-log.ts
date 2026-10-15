import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { AuditEntry, CursorPage } from '../../core/api/models';
import { AuditSearch, PlatformApi } from '../../core/api/platform.api';
import { PageHeader } from '../../shared/ui/page-header';
import { StatusBadge } from '../../shared/ui/status-badge';

/** Platform-wide audit log, or one company's when {@code companyId} is set (company workspace). */
@Component({
  selector: 'occpl-audit-log',
  imports: [FormsModule, PageHeader, StatusBadge, DatePipe, JsonPipe],
  template: `
    <occpl-page-header heading="Audit log" subtitle="Who changed what, newest first" />
    <form class="filters" (ngSubmit)="search()" role="search">
      <div class="field">
        <label for="action">Action starts with</label>
        <input id="action" name="action" [(ngModel)]="action" placeholder="company." />
      </div>
      <div class="field">
        <label for="from">From</label>
        <input id="from" name="from" type="date" [(ngModel)]="from" />
      </div>
      <div class="field">
        <label for="to">To</label>
        <input id="to" name="to" type="date" [(ngModel)]="to" />
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>When</th>
            <th>Action</th>
            <th>Actor</th>
            <th>Target</th>
            <th>Outcome</th>
            <th><span class="sr-only">Details</span></th>
          </tr>
        </thead>
        <tbody>
          @for (e of entries(); track e.id) {
            <tr>
              <td data-label="When">{{ e.occurredAt | date: 'medium' }}</td>
              <td data-label="Action" class="mono">{{ e.action }}</td>
              <td data-label="Actor">{{ actor(e) }}</td>
              <td data-label="Target">
                {{ e.targetType }} <span class="mono muted">{{ e.targetId }}</span>
              </td>
              <td data-label="Outcome"><occpl-status-badge [status]="e.outcome" /></td>
              <td class="actions">
                @if (e.details) {
                  <button
                    type="button"
                    class="btn small link"
                    (click)="toggle(e.id)"
                    [attr.aria-expanded]="open() === e.id"
                  >
                    Details
                  </button>
                }
              </td>
            </tr>
            @if (open() === e.id) {
              <tr>
                <td colspan="6">
                  <pre class="json">{{ e.details | json }}</pre>
                  @if (e.correlationId) {
                    <span class="small muted">Correlation id: {{ e.correlationId }}</span>
                  }
                </td>
              </tr>
            }
          } @empty {
            <tr>
              <td colspan="6" class="muted">{{ loading() ? 'Loading…' : 'No entries.' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @if (next()) {
      <p><button type="button" class="btn" (click)="fetch(next())">Load more</button></p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditLog implements OnInit {
  private readonly api = inject(PlatformApi);
  readonly companyId = input<string | undefined>();

  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly open = signal<string | null>(null);
  protected action = '';
  protected from = '';
  protected to = '';

  ngOnInit(): void {
    this.search();
  }

  protected search(): void {
    this.entries.set([]);
    this.fetch(null);
  }

  protected fetch(cursor: string | null): void {
    const criteria: AuditSearch = {
      action: this.action.trim() || null,
      from: this.from ? new Date(this.from + 'T00:00:00').toISOString() : null,
      to: this.to ? new Date(this.to + 'T23:59:59.999').toISOString() : null,
      cursor,
      size: 50,
    };
    const companyId = this.companyId();
    const request: Observable<CursorPage<AuditEntry>> = companyId
      ? this.api.companyAudit(companyId, criteria)
      : this.api.audit(criteria);
    this.loading.set(true);
    request.subscribe({
      next: (page) => {
        this.entries.update((list) => [...list, ...page.items]);
        this.next.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected toggle(id: string): void {
    this.open.set(this.open() === id ? null : id);
  }

  protected actor(e: AuditEntry): string {
    if (e.actorType === 'SYSTEM') {
      return 'System';
    }
    if (e.actorType === 'SERVICE') {
      return `Service ${e.actorClientId ?? ''}`.trim();
    }
    return e.actorUserId ? `User ${e.actorUserId.slice(0, 8)}…` : 'User';
  }
}
