import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, query } from './api-url';
import { Invitation, InvitationStatus, Member, MembershipStatus, Page, Role } from './models';

export interface MemberSearch {
  status?: MembershipStatus | null;
  role?: string | null;
  q?: string | null;
  page?: number;
  size?: number;
}

export interface NewInvitation {
  email: string;
  roles: string[];
  firstName?: string | null;
  lastName?: string | null;
}

@Injectable({ providedIn: 'root' })
export class MembersApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  list(companyId: string, criteria: MemberSearch = {}): Observable<Page<Member>> {
    return this.http.get<Page<Member>>(this.api('/companies', companyId, '/memberships'), {
      params: query({ ...criteria }),
    });
  }

  roles(companyId: string): Observable<Role[]> {
    return this.http.get<Role[]>(this.api('/companies', companyId, '/roles'));
  }

  changeStatus(
    companyId: string,
    membershipId: string,
    action: 'suspend' | 'reactivate' | 'deactivate',
    reason: string | null,
  ): Observable<Member> {
    return this.http.post<Member>(
      this.api('/companies', companyId, '/memberships', membershipId, '/' + action),
      {
        reason,
      },
    );
  }

  replaceRoles(companyId: string, membershipId: string, roles: string[]): Observable<Member> {
    return this.http.put<Member>(
      this.api('/companies', companyId, '/memberships', membershipId, '/roles'),
      { roles },
    );
  }

  invitations(
    companyId: string,
    status: InvitationStatus | null,
    page = 0,
  ): Observable<Page<Invitation>> {
    return this.http.get<Page<Invitation>>(this.api('/companies', companyId, '/invitations'), {
      params: query({ status, page }),
    });
  }

  invite(companyId: string, invitation: NewInvitation): Observable<Invitation> {
    return this.http.post<Invitation>(
      this.api('/companies', companyId, '/invitations'),
      invitation,
    );
  }

  resend(companyId: string, invitationId: string): Observable<Invitation> {
    return this.http.post<Invitation>(
      this.api('/companies', companyId, '/invitations', invitationId, '/resend'),
      {},
    );
  }

  revoke(companyId: string, invitationId: string): Observable<Invitation> {
    return this.http.post<Invitation>(
      this.api('/companies', companyId, '/invitations', invitationId, '/revoke'),
      {},
    );
  }
}
