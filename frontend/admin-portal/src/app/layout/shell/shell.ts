import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorNotifier } from '../../core/errors/error-notifier';
import { SessionService } from '../../core/session/session.service';

interface NavItem {
  readonly label: string;
  readonly link: string;
  readonly exact?: boolean;
}

const PLATFORM_NAV: (NavItem & { permission?: string })[] = [
  { label: 'Dashboard', link: '/platform', exact: true },
  { label: 'Companies', link: '/platform/companies', permission: 'platform.companies.read' },
  { label: 'Users', link: '/platform/users', permission: 'platform.users.read' },
  { label: 'Products', link: '/platform/products', permission: 'platform.products.read' },
  { label: 'Plans', link: '/platform/plans', permission: 'platform.plans.read' },
  {
    label: 'Subscriptions',
    link: '/platform/subscriptions',
    permission: 'platform.subscriptions.read',
  },
  { label: 'Invoices', link: '/platform/invoices', permission: 'platform.subscriptions.read' },
  {
    label: 'Provisioning',
    link: '/platform/provisioning',
    permission: 'platform.provisioning.read',
  },
  { label: 'Audit log', link: '/platform/audit', permission: 'platform.audit.read' },
];

/** Signed-in layout: header with user menu, navigation by permission, global error and MFA notices. */
@Component({
  selector: 'occpl-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly notifier = inject(ErrorNotifier);
  protected readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);

  protected readonly navOpen = signal(false);
  private readonly url = signal('');
  /** The MFA page already explains the situation; no banner there. */
  protected readonly showMfaBanner = computed(
    () => this.session.mfaRequired() && !this.url().startsWith('/mfa-required'),
  );
  protected readonly hasNav = computed(
    () => this.platformNav().length > 0 || this.companies().length > 0,
  );

  protected readonly platformNav = computed(() => {
    const permissions = this.session.platformPermissions();
    if (permissions.size === 0) {
      return [];
    }
    return PLATFORM_NAV.filter((item) => !item.permission || permissions.has(item.permission));
  });

  protected readonly companies = computed(() =>
    this.session.memberships().map((m) => ({
      label: m.companyName,
      link: `/companies/${m.companyId}`,
      status: m.companyStatus,
    })),
  );

  protected readonly userName = computed(() => {
    const me = this.session.me();
    return me?.displayName || me?.email || '';
  });

  constructor() {
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((e) => {
        this.navOpen.set(false);
        this.url.set((e as NavigationEnd).urlAfterRedirects);
      });
  }

  protected signOut(): void {
    void this.auth.logout();
  }

  protected signInWithMfa(): void {
    void this.auth.login('/', { forceLogin: true });
  }
}
