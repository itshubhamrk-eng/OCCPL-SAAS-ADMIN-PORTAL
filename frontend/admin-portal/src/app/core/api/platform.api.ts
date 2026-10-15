import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, query } from './api-url';
import {
  AuditEntry,
  CompanyProduct,
  CursorPage,
  JobStatus,
  Page,
  PlatformUser,
  ProvisioningJob,
  Role,
  UserMembership,
  UserStatus,
} from './models';

export interface AuditSearch {
  companyId?: string | null;
  actorUserId?: string | null;
  action?: string | null;
  from?: string | null;
  to?: string | null;
  cursor?: string | null;
  size?: number;
}

/** Platform users and roles, provisioning, products of a company, and the audit log. */
@Injectable({ providedIn: 'root' })
export class PlatformApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  users(criteria: {
    q?: string | null;
    status?: UserStatus | null;
    after?: string | null;
    size?: number;
  }): Observable<CursorPage<PlatformUser>> {
    return this.http.get<CursorPage<PlatformUser>>(this.api('/users'), {
      params: query({ ...criteria }),
    });
  }

  user(id: string): Observable<PlatformUser> {
    return this.http.get<PlatformUser>(this.api('/users', id));
  }

  setUserEnabled(id: string, enabled: boolean, reason: string | null): Observable<PlatformUser> {
    return enabled
      ? this.http.post<PlatformUser>(this.api('/users', id, '/enable'), {})
      : this.http.post<PlatformUser>(this.api('/users', id, '/disable'), { reason });
  }

  userMemberships(id: string): Observable<UserMembership[]> {
    return this.http.get<UserMembership[]>(this.api('/users', id, '/memberships'));
  }

  platformRoles(): Observable<Role[]> {
    return this.http.get<Role[]>(this.api('/platform/roles'));
  }

  replaceUserRoles(id: string, roles: string[]): Observable<{ userId: string; roles: string[] }> {
    return this.http.put<{ userId: string; roles: string[] }>(
      this.api('/platform/users', id, '/roles'),
      { roles },
    );
  }

  jobs(criteria: {
    status?: JobStatus | null;
    companyId?: string | null;
    productId?: string | null;
    page?: number;
    size?: number;
  }): Observable<Page<ProvisioningJob>> {
    return this.http.get<Page<ProvisioningJob>>(this.api('/platform/provisioning-jobs'), {
      params: query({ ...criteria }),
    });
  }

  job(id: string): Observable<ProvisioningJob> {
    return this.http.get<ProvisioningJob>(this.api('/platform/provisioning-jobs', id));
  }

  retryJob(id: string): Observable<ProvisioningJob> {
    return this.http.post<ProvisioningJob>(
      this.api('/platform/provisioning-jobs', id, '/retry'),
      {},
    );
  }

  cancelJob(id: string, reason: string | null): Observable<ProvisioningJob> {
    return this.http.post<ProvisioningJob>(this.api('/platform/provisioning-jobs', id, '/cancel'), {
      reason,
    });
  }

  companyProducts(companyId: string): Observable<CompanyProduct[]> {
    return this.http.get<CompanyProduct[]>(this.api('/companies', companyId, '/products'));
  }

  audit(criteria: AuditSearch): Observable<CursorPage<AuditEntry>> {
    return this.http.get<CursorPage<AuditEntry>>(this.api('/audit-logs'), {
      params: query({ ...criteria }),
    });
  }

  companyAudit(companyId: string, criteria: AuditSearch): Observable<CursorPage<AuditEntry>> {
    return this.http.get<CursorPage<AuditEntry>>(this.api('/companies', companyId, '/audit-logs'), {
      params: query({ ...criteria, companyId: null }),
    });
  }
}
