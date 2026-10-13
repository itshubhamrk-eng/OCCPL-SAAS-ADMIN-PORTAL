import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from '../../core/session/session.service';

/** "/" sends platform staff to the platform area and company users to their (last) company. */
export const homeRedirect: CanActivateFn = () =>
  inject(Router).parseUrl(inject(SessionService).homeUrl());
