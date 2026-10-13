import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from '../session/session.service';
import { AuthService } from './auth.service';

/** Requires a signed-in user whose /me has been loaded; otherwise starts sign-in. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  if (!auth.isAuthenticated()) {
    await auth.login(state.url);
    return false;
  }
  try {
    await inject(SessionService).load();
    return true;
  } catch {
    return false; // the error is shown by the API error interceptor
  }
};

/** Platform area: any platform permission, or the given one. */
export function platformGuard(permission?: string): CanActivateFn {
  return () => {
    const session = inject(SessionService);
    const allowed = permission
      ? session.hasPlatform(permission)
      : session.platformPermissions().size > 0;
    if (allowed) {
      return true;
    }
    return inject(Router).parseUrl(session.mfaRequired() ? '/mfa-required' : '/forbidden');
  };
}

/** Company workspace: an active membership in the company, or platform read access to companies. */
export const companyGuard: CanActivateFn = (route) => {
  const session = inject(SessionService);
  const companyId = route.paramMap.get('companyId');
  if (session.membership(companyId) || session.hasPlatform('platform.companies.read')) {
    if (companyId && session.membership(companyId)) {
      session.rememberCompany(companyId);
    }
    return true;
  }
  return inject(Router).parseUrl('/forbidden');
};

/** Page inside a company that needs a specific permission. */
export function companyPermissionGuard(
  companyPermission: string,
  platformPermission?: string,
): CanActivateFn {
  return (route) => {
    const session = inject(SessionService);
    const companyId = route.pathFromRoot.map((r) => r.paramMap.get('companyId')).find((id) => !!id);
    return (
      session.can(companyId, companyPermission, platformPermission) ||
      inject(Router).parseUrl('/forbidden')
    );
  };
}
