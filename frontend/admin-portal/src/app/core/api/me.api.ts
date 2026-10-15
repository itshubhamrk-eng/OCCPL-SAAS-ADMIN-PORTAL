import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SUPPRESS_GLOBAL_ERROR } from '../http/api-error.interceptor';
import { apiUrlBuilder } from './api-url';
import { AcceptedInvitation, Me, Profile } from './models';

@Injectable({ providedIn: 'root' })
export class MeApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  me(): Observable<Me> {
    return this.http.get<Me>(this.api('/me'));
  }

  profile(): Observable<Profile> {
    return this.http.get<Profile>(this.api('/me/profile'));
  }

  updateProfile(profile: Omit<Profile, 'userId' | 'email'>): Observable<Profile> {
    return this.http.put<Profile>(this.api('/me/profile'), profile);
  }

  acceptInvitation(token: string): Observable<AcceptedInvitation> {
    // The page explains failures itself (expired, wrong account), so no global error banner.
    return this.http.post<AcceptedInvitation>(
      this.api('/invitations/accept'),
      { token },
      { context: new HttpContext().set(SUPPRESS_GLOBAL_ERROR, true) },
    );
  }
}
