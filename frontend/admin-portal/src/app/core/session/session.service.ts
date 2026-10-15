import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MeApi } from '../api/me.api';
import { Me, MyMembership } from '../api/models';

const LAST_COMPANY_KEY = 'occpl.lastCompanyId';

/**
 * The signed-in user as Core sees them (GET /me): platform roles/permissions and active company
 * memberships. The UI uses it only to show or hide things; Core enforces every permission itself.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly meApi = inject(MeApi);
  private readonly current = signal<Me | null>(null);
  private loading?: Promise<Me>;

  readonly me = this.current.asReadonly();
  readonly platformPermissions = computed(
    () => new Set(this.current()?.platform.permissions ?? []),
  );
  readonly isPlatformStaff = computed(() => (this.current()?.platform.roles.length ?? 0) > 0);
  readonly mfaRequired = computed(() => this.current()?.platform.mfaRequired ?? false);
  readonly memberships = computed(() => this.current()?.memberships ?? []);

  load(force = false): Promise<Me> {
    if (!force && this.current()) {
      return Promise.resolve(this.current() as Me);
    }
    this.loading ??= firstValueFrom(this.meApi.me())
      .then((me) => {
        this.current.set(me);
        return me;
      })
      .finally(() => (this.loading = undefined));
    return this.loading;
  }

  hasPlatform(permission: string): boolean {
    return this.platformPermissions().has(permission);
  }

  membership(companyId: string | null | undefined): MyMembership | undefined {
    return companyId ? this.memberships().find((m) => m.companyId === companyId) : undefined;
  }

  /** Company permission through membership, or the platform permission that covers it. */
  can(
    companyId: string | null | undefined,
    companyPermission: string,
    platformPermission?: string,
  ): boolean {
    if (platformPermission && this.hasPlatform(platformPermission)) {
      return true;
    }
    return this.membership(companyId)?.permissions.includes(companyPermission) ?? false;
  }

  rememberCompany(companyId: string): void {
    try {
      localStorage.setItem(LAST_COMPANY_KEY, companyId);
    } catch {
      // storage unavailable: no preference is kept
    }
  }

  /** Where to land after sign-in: platform area for staff, otherwise the last or only company. */
  homeUrl(): string {
    if (this.platformPermissions().size > 0) {
      return '/platform';
    }
    const memberships = this.memberships();
    let last: string | null = null;
    try {
      last = localStorage.getItem(LAST_COMPANY_KEY);
    } catch {
      last = null;
    }
    const target = memberships.find((m) => m.companyId === last) ?? memberships[0];
    if (target) {
      return `/companies/${target.companyId}`;
    }
    return this.isPlatformStaff() ? '/platform' : '/no-access';
  }
}
