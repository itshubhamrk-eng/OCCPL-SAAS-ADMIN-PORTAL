import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Page, Subscription, SubscriptionStatus } from '../../../core/api/models';
import { SubscriptionsApi } from '../../../core/api/subscriptions.api';
import { MoneyPipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-subscriptions-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge, DatePipe, MoneyPipe],
  template: `
    <occpl-page-header
      heading="Subscriptions"
      subtitle="Plans assigned to companies; manage them from the company's subscription page"
    />
    <div class="filters">
      <div class="field">
        <label for="status">Status</label>
        <select id="status" [(ngModel)]="statusFilter" (ngModelChange)="search(0)">
          <option value="">All</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s.toLowerCase().replace('_', ' ') }}</option>
          }
        </select>
      </div>
    </div>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Company</th>
            <th>Plan</th>
            <th>Price</th>
            <th>Status</th>
            <th>Current period ends</th>
          </tr>
        </thead>
        <tbody>
          @for (s of page()?.items ?? []; track s.id) {
            <tr>
              <td data-label="Company">
                <a [routerLink]="['/companies', s.companyId, 'subscription']">Open company</a>
              </td>
              <td data-label="Plan">
                {{ s.planName }}
                <div class="mono muted">{{ s.planCode }}</div>
              </td>
              <td data-label="Price">{{ s.priceMinor | money: s.currency }}</td>
              <td data-label="Status">
                <occpl-status-badge [status]="s.status" />
                @if (s.cancelAtPeriodEnd) {
                  <div class="small muted">Ends at period end</div>
                }
              </td>
              <td data-label="Period ends">{{ s.currentPeriodEnd | date: 'mediumDate' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">
                {{ page() ? 'No subscriptions match.' : 'Loading…' }}
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SubscriptionsList implements OnInit {
  private readonly api = inject(SubscriptionsApi);
  readonly status = input<string | undefined>();
  protected readonly statuses: SubscriptionStatus[] = [
    'TRIAL',
    'ACTIVE',
    'PAST_DUE',
    'SUSPENDED',
    'CANCELLED',
    'EXPIRED',
  ];
  protected readonly page = signal<Page<Subscription> | null>(null);
  protected statusFilter = '';

  ngOnInit(): void {
    this.statusFilter = this.status() ?? '';
    this.search(0);
  }

  protected search(page: number): void {
    this.api
      .search({ status: (this.statusFilter || null) as SubscriptionStatus | null, page, size: 20 })
      .subscribe((p) => this.page.set(p));
  }
}
