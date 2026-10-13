import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { MeApi } from '../../core/api/me.api';
import { AcceptedInvitation } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { isApiError } from '../../shared/forms/server-errors';

/**
 * Landing page of invitation links (?token=…). The token is removed from the address bar at once and
 * sent to Core in the request body; Core accepts it only for the signed-in user it was addressed to.
 */
@Component({
  selector: 'occpl-accept-invitation',
  imports: [RouterLink],
  template: `
    <div class="status-page">
      @if (accepted(); as result) {
        <h1>Welcome to {{ result.companyName }}</h1>
        <p>You joined as {{ result.roles.join(', ') }}.</p>
        <a class="btn primary" [routerLink]="['/companies', result.companyId]"
          >Open {{ result.companyName }}</a
        >
      } @else if (error(); as message) {
        <h1>The invitation could not be accepted</h1>
        <p>{{ message }}</p>
        <a class="btn" routerLink="/">Go to start page</a>
      } @else {
        <p role="status">Accepting your invitation…</p>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AcceptInvitation implements OnInit {
  private readonly api = inject(MeApi);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly accepted = signal<AcceptedInvitation | null>(null);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const token = this.router.parseUrl(this.router.url).queryParamMap.get('token');
    await this.router.navigate([], { queryParams: {}, replaceUrl: true });
    if (!token) {
      this.error.set('The link is incomplete. Open the link from your invitation email again.');
      return;
    }
    try {
      this.accepted.set(await firstValueFrom(this.api.acceptInvitation(token)));
      await this.session.load(true);
    } catch (e) {
      const code = isApiError(e) ? e.code : '';
      this.error.set(
        code === 'INVITATION_EXPIRED'
          ? 'This invitation has expired. Ask your company administrator to send a new one.'
          : code === 'NOT_FOUND'
            ? 'This invitation was not found. It may have been used already, revoked, or sent to a different email address than the one you signed in with.'
            : isApiError(e)
              ? e.message
              : 'Something went wrong. Please try again.',
      );
    }
  }
}
