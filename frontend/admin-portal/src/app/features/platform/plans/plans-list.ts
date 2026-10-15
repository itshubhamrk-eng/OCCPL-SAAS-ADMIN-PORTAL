import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Page, Plan, PlanStatus } from '../../../core/api/models';
import { SubscriptionsApi } from '../../../core/api/subscriptions.api';
import { SessionService } from '../../../core/session/session.service';
import { HumanizePipe, MoneyPipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-plans-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge, HumanizePipe, MoneyPipe],
  template: `
    <occpl-page-header heading="Plans" subtitle="Prices, included products and limits">
      @if (session.hasPlatform('platform.plans.manage')) {
        <a class="btn primary" routerLink="/platform/plans/new">New plan</a>
      }
    </occpl-page-header>
    <form class="filters" (ngSubmit)="search(0)" role="search">
      <div class="field">
        <label for="q">Search</label>
        <input id="q" name="q" [(ngModel)]="q" placeholder="Name or code" />
      </div>
      <div class="field">
        <label for="status">Status</label>
        <select id="status" name="status" [(ngModel)]="status" (ngModelChange)="search(0)">
          <option value="">All</option>
          <option value="DRAFT">draft</option>
          <option value="ACTIVE">active</option>
          <option value="ARCHIVED">archived</option>
        </select>
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Plan</th>
            <th>Billing</th>
            <th>Price</th>
            <th>Products</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          @for (p of page()?.items ?? []; track p.id) {
            <tr>
              <td data-label="Plan">
                <a [routerLink]="[p.id]">{{ p.name }}</a>
                <div class="mono muted">{{ p.code }}</div>
              </td>
              <td data-label="Billing">
                {{ p.billingPeriod | humanize }}
                @if (p.trialDays) {
                  · {{ p.trialDays }}-day trial
                }
              </td>
              <td data-label="Price">
                @for (price of p.prices; track price.currency) {
                  <div>{{ price.amountMinor | money: price.currency }}</div>
                }
              </td>
              <td data-label="Products">{{ productNames(p) }}</td>
              <td data-label="Status"><occpl-status-badge [status]="p.status" /></td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">{{ page() ? 'No plans match.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlansList implements OnInit {
  private readonly api = inject(SubscriptionsApi);
  protected readonly session = inject(SessionService);
  protected readonly page = signal<Page<Plan> | null>(null);
  protected q = '';
  protected status = '';

  ngOnInit(): void {
    this.search(0);
  }

  protected search(page: number): void {
    this.api
      .plans({
        q: this.q.trim() || null,
        status: (this.status || null) as PlanStatus | null,
        page,
        size: 20,
      })
      .subscribe((p) => this.page.set(p));
  }

  protected productNames(plan: Plan): string {
    return plan.products.map((p) => p.productName).join(', ') || '—';
  }
}
