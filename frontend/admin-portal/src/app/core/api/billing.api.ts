import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, query } from './api-url';
import { BillingStatus, Invoice, InvoiceStatus, Page } from './models';

@Injectable({ providedIn: 'root' })
export class BillingApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  status(): Observable<BillingStatus> {
    return this.http.get<BillingStatus>(this.api('/billing/status'));
  }

  forCompany(companyId: string, status: InvoiceStatus | null, page = 0): Observable<Page<Invoice>> {
    return this.http.get<Page<Invoice>>(this.api('/companies', companyId, '/invoices'), {
      params: query({ status, page, size: 20 }),
    });
  }

  get(companyId: string, invoiceId: string): Observable<Invoice> {
    return this.http.get<Invoice>(this.api('/companies', companyId, '/invoices', invoiceId));
  }

  create(companyId: string, subscriptionId: string): Observable<Invoice> {
    return this.http.post<Invoice>(
      this.api('/companies', companyId, '/subscriptions', subscriptionId, '/invoices'),
      {},
    );
  }

  cancel(companyId: string, invoiceId: string): Observable<Invoice> {
    return this.http.post<Invoice>(
      this.api('/companies', companyId, '/invoices', invoiceId, '/cancel'),
      {},
    );
  }

  refresh(companyId: string, invoiceId: string): Observable<Invoice> {
    return this.http.post<Invoice>(
      this.api('/companies', companyId, '/invoices', invoiceId, '/refresh'),
      {},
    );
  }

  search(criteria: { status?: InvoiceStatus | null; page?: number }): Observable<Page<Invoice>> {
    return this.http.get<Page<Invoice>>(this.api('/platform/invoices'), {
      params: query({ ...criteria, size: 20 }),
    });
  }
}
