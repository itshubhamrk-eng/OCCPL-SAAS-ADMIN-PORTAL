import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { BillingApi } from '../../core/api/billing.api';
import { BillingStatus, Invoice, InvoiceStatus, Page } from '../../core/api/models';
import { ActionDialog } from '../../shared/ui/action-dialog';
import { MoneyPipe } from '../../shared/ui/format.pipes';
import { Pager } from '../../shared/ui/pager';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

/**
 * Billing history. Invoices are issued and collected by the payment gateway (Razorpay); "Pay" opens
 * the gateway's hosted invoice page. Staff can cancel unpaid invoices and re-read their state.
 */
@Component({
  selector: 'occpl-company-billing',
  imports: [FormsModule, StatusBadge, ActionDialog, Pager, DatePipe, MoneyPipe],
  template: `
    <div class="row">
      <h2>Billing</h2>
      <span class="spacer"></span>
      <label class="sr-only" for="inv-status">Status</label>
      <select
        id="inv-status"
        class="status-filter"
        [(ngModel)]="statusFilter"
        (ngModelChange)="load(0)"
      >
        <option value="">All invoices</option>
        <option value="ISSUED">Unpaid</option>
        <option value="PAID">Paid</option>
        <option value="EXPIRED">Expired</option>
        <option value="CANCELLED">Cancelled</option>
      </select>
    </div>
    @if (staff() && billing() && !billing()!.enabled) {
      <p class="notice warning">
        Billing is not configured: set the Razorpay keys to issue invoices.
      </p>
    }
    @if (notice(); as message) {
      <p class="notice success" role="status">{{ message }}</p>
    }
    <occpl-action-dialog
      #cancelDialog
      heading="Cancel invoice"
      confirmLabel="Cancel invoice"
      [danger]="true"
      message="The payment link stops working. A new invoice can be issued for the same period."
      (confirmed)="cancel()"
    />
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Invoice</th>
            <th>Period</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Issued</th>
            <th><span class="sr-only">Actions</span></th>
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
              <td data-label="Period">
                {{ inv.periodStart | date: 'mediumDate' }} –
                {{ inv.periodEnd | date: 'mediumDate' }}
              </td>
              <td data-label="Amount">
                {{ inv.amountMinor | money: inv.currency }}
                @if (inv.status === 'PARTIALLY_PAID') {
                  <div class="small muted">
                    {{ inv.amountPaidMinor | money: inv.currency }} paid
                  </div>
                }
              </td>
              <td data-label="Status">
                <occpl-status-badge [status]="inv.status" />
                @if (inv.status === 'ISSUED' && inv.expiresAt) {
                  <div class="small muted">Pay by {{ inv.expiresAt | date: 'mediumDate' }}</div>
                }
                @if (inv.status === 'PAID' && inv.paidAt) {
                  <div class="small muted">{{ inv.paidAt | date: 'mediumDate' }}</div>
                }
                @if (staff() && inv.paymentApplied === 'NOT_APPLICABLE') {
                  <div class="small field-error">Paid after the subscription ended — review</div>
                }
                @if (staff() && inv.lastError) {
                  <div class="small field-error">{{ inv.lastError }}</div>
                }
              </td>
              <td data-label="Issued">{{ (inv.issuedAt | date: 'mediumDate') ?? '—' }}</td>
              <td class="actions">
                @if (inv.paymentUrl) {
                  <a
                    class="btn small primary"
                    [href]="inv.paymentUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    >Pay</a
                  >
                }
                @if (staff() && (inv.status === 'ISSUED' || inv.status === 'PARTIALLY_PAID')) {
                  <button type="button" class="btn small" (click)="refresh(inv)">
                    Check payment
                  </button>
                  <button
                    type="button"
                    class="btn small danger"
                    (click)="target = inv; cancelDialog.open()"
                  >
                    Cancel
                  </button>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">{{ page() ? 'No invoices yet.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="load($event)" />
    <p class="muted small">
      Invoices are issued by our payment partner (Razorpay), which also emails the payment link and
      the tax invoice.
    </p>
  `,
  styles: `
    .status-filter {
      max-width: 12rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyBilling implements OnInit {
  private readonly api = inject(BillingApi);
  private readonly context = inject(CompanyContext);

  protected readonly page = signal<Page<Invoice> | null>(null);
  protected readonly billing = signal<BillingStatus | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected statusFilter = '';
  protected target: Invoice | null = null;

  protected staff(): boolean {
    return this.context.isPlatform('platform.subscriptions.manage');
  }

  ngOnInit(): void {
    this.load(0);
    if (this.staff()) {
      this.api.status().subscribe((s) => this.billing.set(s));
    }
  }

  protected load(page: number): void {
    this.api
      .forCompany(this.context.id(), (this.statusFilter || null) as InvoiceStatus | null, page)
      .subscribe((p) => this.page.set(p));
  }

  protected async refresh(invoice: Invoice): Promise<void> {
    const updated = await firstValueFrom(this.api.refresh(this.context.id(), invoice.id));
    this.notice.set(
      updated.status === 'PAID'
        ? 'Payment received; the subscription was updated.'
        : 'No payment yet.',
    );
    this.load(this.page()?.page ?? 0);
  }

  protected async cancel(): Promise<void> {
    if (this.target) {
      await firstValueFrom(this.api.cancel(this.context.id(), this.target.id));
      this.notice.set('Invoice cancelled.');
      this.load(this.page()?.page ?? 0);
    }
  }
}
