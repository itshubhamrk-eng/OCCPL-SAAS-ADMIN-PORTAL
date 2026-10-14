import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { BillingApi } from '../../core/api/billing.api';
import { Invoice } from '../../core/api/models';
import { CompanyBilling } from './company-billing';
import { CompanyContext } from './company-context';

const invoice = (overrides: Partial<Invoice>): Invoice => ({
  id: 'i-1',
  companyId: 'c-acme',
  subscriptionId: 's-1',
  status: 'ISSUED',
  description: 'Notes Basic — 13 Oct 2026 to 13 Nov 2026',
  currency: 'INR',
  amountMinor: 49900,
  amountPaidMinor: 0,
  periodStart: '2026-10-13T00:00:00Z',
  periodEnd: '2026-11-13T00:00:00Z',
  gatewayInvoiceId: 'inv_1',
  gatewayNumber: 'OCC/1',
  paymentUrl: 'https://rzp.io/i/inv_1',
  issuedAt: '2026-10-13T00:00:00Z',
  expiresAt: '2026-10-27T00:00:00Z',
  paidAt: null,
  paymentApplied: null,
  lastError: null,
  createdAt: '2026-10-13T00:00:00Z',
  payments: null,
  ...overrides,
});

describe('CompanyBilling', () => {
  function setup(staff: boolean, items: Invoice[]) {
    const api = {
      forCompany: vi
        .fn()
        .mockReturnValue(of({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 })),
      status: vi
        .fn()
        .mockReturnValue(of({ enabled: true, webhooksEnabled: true, gateway: 'RAZORPAY' })),
      cancel: vi.fn().mockReturnValue(of(invoice({ status: 'CANCELLED' }))),
      refresh: vi.fn().mockReturnValue(of(invoice({ status: 'PAID' }))),
    };
    TestBed.configureTestingModule({
      imports: [CompanyBilling],
      providers: [
        { provide: BillingApi, useValue: api },
        { provide: CompanyContext, useValue: { id: () => 'c-acme', isPlatform: () => staff } },
      ],
    });
    const fixture = TestBed.createComponent(CompanyBilling);
    return { fixture, api, el: fixture.nativeElement as HTMLElement };
  }

  it('offers the gateway payment link to company admins and no staff actions', async () => {
    const { fixture, el, api } = setup(false, [
      invoice({}),
      invoice({ id: 'i-2', status: 'PAID', paymentUrl: null }),
    ]);
    await fixture.whenStable();
    const pay = el.querySelectorAll('a.btn');
    expect(pay.length).toBe(1);
    expect(pay[0].getAttribute('href')).toBe('https://rzp.io/i/inv_1');
    expect(pay[0].getAttribute('rel')).toContain('noopener');
    expect(el.querySelectorAll('td.actions button').length).toBe(0);
    expect(el.textContent).toContain('₹499.00');
    expect(api.status).not.toHaveBeenCalled();
  });

  it('lets staff re-check payment and cancel unpaid invoices', async () => {
    const { fixture, el, api } = setup(true, [invoice({ lastError: null })]);
    await fixture.whenStable();
    (el.querySelector('button.btn.small:not(.danger)') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.refresh).toHaveBeenCalledWith('c-acme', 'i-1');
    expect(el.querySelector('[role="status"]')?.textContent).toContain('Payment received');

    (el.querySelector('td.actions button.danger') as HTMLButtonElement).click();
    await fixture.whenStable();
    (el.querySelector('dialog button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.cancel).toHaveBeenCalledWith('c-acme', 'i-1');
  });
});
