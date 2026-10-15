import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BillingApi } from '../../core/api/billing.api';
import { BillingStatus, Invoice, InvoiceStatus, Page } from '../../core/api/models';
import { MoneyPipe } from '../../shared/ui/format.pipes';
import { PageHeader } from '../../shared/ui/page-header';
import { Pager } from '../../shared/ui/pager';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'occpl-invoices-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge, DatePipe, MoneyPipe],
  template: `
    <occpl-page-header
      heading="Invoices"
      subtitle="Issued through Razorpay; payments renew subscriptions automatically"
    />
    @if (billing(); as b) {
      @if (!b.enabled) {
        <p class="notice warning">
          Billing is not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET). No invoices can be
          issued.
        </p>
      } @else if (!b.webhooksEnabled) {
        <p class="notice warning">
          Payment notifications are not configured (RAZORPAY_WEBHOOK_SECRET); use "Check payment" on
          each invoice.
        </p>
      }
    }
    <div class="filters">
      <div class="field">
        <label for="status">Status</label>
        <select id="status" [(ngModel)]="statusFilter" (ngModelChange)="load(0)">
          <option value="">All</option>
          <option value="ISSUED">Unpaid</option>
          <option value="PARTIALLY_PAID">Partially paid</option>
          <option value="PAID">Paid</option>
          <option value="ISSUE_FAILED">Issue failed</option>
          <option value="EXPIRED">Expired</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>
    </div>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Invoice</th>
            <th>Company</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          @for (inv of page()?.items ?? []; track inv.id) {
            <tr>
              <td data-label="Invoice">
                {{ inv.description }}
                @if (inv.gatewayNumber) {
                  <div class="mono muted">{{ inv.gatewayNumber }}</div>
                }
              </td>
              <td data-label="Company">
                <a [routerLink]="['/companies', inv.companyId, 'billing']">Open company billing</a>
              </td>
              <td data-label="Amount">{{ inv.amountMinor | money: inv.currency }}</td>
              <td data-label="Status">
                <occpl-status-badge [status]="inv.status" />
                @if (inv.lastError) {
                  <div class="small field-error">{{ inv.lastError }}</div>
                }
              </td>
              <td data-label="Created">{{ inv.createdAt | date: 'medium' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">{{ page() ? 'No invoices.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="load($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicesList implements OnInit {
  private readonly api = inject(BillingApi);
  readonly status = input<string | undefined>();
  protected readonly page = signal<Page<Invoice> | null>(null);
  protected readonly billing = signal<BillingStatus | null>(null);
  protected statusFilter = '';

  ngOnInit(): void {
    this.statusFilter = this.status() ?? '';
    this.api.status().subscribe((s) => this.billing.set(s));
    this.load(0);
  }

  protected load(page: number): void {
    this.api
      .search({ status: (this.statusFilter || null) as InvoiceStatus | null, page })
      .subscribe((p) => this.page.set(p));
  }
}
