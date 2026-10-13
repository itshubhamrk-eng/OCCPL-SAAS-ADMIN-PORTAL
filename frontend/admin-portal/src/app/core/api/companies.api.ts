import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, ifMatch, query } from './api-url';
import {
  Company,
  CompanyChanges,
  CompanyContact,
  CompanySettings,
  CompanyStatus,
  Contact,
  ContactType,
  NewCompany,
  Page,
  StatusChange,
} from './models';

export interface CompanySearch {
  status?: CompanyStatus | null;
  q?: string | null;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class CompaniesApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  search(criteria: CompanySearch = {}): Observable<Page<Company>> {
    return this.http.get<Page<Company>>(this.api('/companies'), { params: query({ ...criteria }) });
  }

  get(id: string): Observable<Company> {
    return this.http.get<Company>(this.api('/companies', id));
  }

  create(company: NewCompany): Observable<Company> {
    return this.http.post<Company>(this.api('/companies'), company);
  }

  update(company: Company, changes: CompanyChanges): Observable<Company> {
    return this.http.patch<Company>(this.api('/companies', company.id), changes, {
      headers: ifMatch(company.version),
    });
  }

  changeStatus(
    id: string,
    action: 'activate' | 'suspend' | 'deactivate',
    reason: string | null,
  ): Observable<Company> {
    return this.http.post<Company>(this.api('/companies', id, '/' + action), { reason });
  }

  statusHistory(id: string): Observable<StatusChange[]> {
    return this.http.get<StatusChange[]>(this.api('/companies', id, '/status-history'));
  }

  settings(id: string): Observable<CompanySettings> {
    return this.http.get<CompanySettings>(this.api('/companies', id, '/settings'));
  }

  saveSettings(
    id: string,
    settings: Omit<CompanySettings, 'version'>,
    version: number,
  ): Observable<CompanySettings> {
    return this.http.put<CompanySettings>(this.api('/companies', id, '/settings'), settings, {
      headers: ifMatch(version),
    });
  }

  contacts(id: string): Observable<CompanyContact[]> {
    return this.http.get<CompanyContact[]>(this.api('/companies', id, '/contacts'));
  }

  saveContact(id: string, type: ContactType, contact: Contact): Observable<CompanyContact> {
    return this.http.put<CompanyContact>(this.api('/companies', id, '/contacts', type), contact);
  }

  deleteContact(id: string, type: ContactType): Observable<void> {
    return this.http.delete<void>(this.api('/companies', id, '/contacts', type));
  }
}
