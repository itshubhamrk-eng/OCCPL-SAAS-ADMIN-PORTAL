import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, ifMatch, query } from './api-url';
import { Lead, LeadData, LeadStatus, Page } from './models';

@Injectable({ providedIn: 'root' })
export class LeadsApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  search(
    criteria: {
      status?: LeadStatus | null;
      q?: string | null;
      page?: number;
      size?: number;
    } = {},
  ): Observable<Page<Lead>> {
    return this.http.get<Page<Lead>>(this.api('/leads'), { params: query({ ...criteria }) });
  }

  get(id: string): Observable<Lead> {
    return this.http.get<Lead>(this.api('/leads', id));
  }

  register(lead: LeadData): Observable<Lead> {
    return this.http.post<Lead>(this.api('/leads'), lead);
  }

  update(lead: Lead, changes: LeadData): Observable<Lead> {
    return this.http.patch<Lead>(this.api('/leads', lead.id), changes, {
      headers: ifMatch(lead.version),
    });
  }

  changeStatus(id: string, status: LeadStatus, reason: string | null): Observable<Lead> {
    return this.http.post<Lead>(this.api('/leads', id, '/status'), { status, reason });
  }
}
