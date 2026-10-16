import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorNotifier } from '../../core/errors/error-notifier';
import { SessionService } from '../../core/session/session.service';
import { ThemeService } from '../../core/theme/theme.service';

interface NavItem {
  readonly label: string;
  readonly link: string;
  readonly icon: string;
  readonly exact?: boolean;
}

const NAV_KEY = 'occpl.nav-collapsed';

const ICONS = {
  dashboard: 'M3 12l9-8 9 8M5 10v10h5v-6h4v6h5V10',
  companies: 'M4 21V5l8-3v19M12 9h8v12M4 21h16',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
  products: 'M21 8l-9-5-9 5v8l9 5 9-5zM3 8l9 5 9-5M12 13v8',
  plans: 'M4 6h16M4 12h16M4 18h10',
  subscriptions: 'M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3',
  invoices: 'M6 2h12v20l-3-2-3 2-3-2-3 2zM9 7h6M9 11h6',
  provisioning:
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19 12l2-1-2-4-2 1-2-1V5h-4v2l-2 1-2-1-2 4 2 1v2l-2 1 2 4 2-1 2 1v2h4v-2l2-1 2 1 2-4-2-1z',
  audit: 'M9 12l2 2 4-4M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z',
};

const PLATFORM_NAV: (NavItem & { permission?: string })[] = [
  { icon: ICONS.dashboard, label: 'Dashboard', link: '/platform', exact: true },
  {
    icon: ICONS.companies,
    label: 'Companies',
    link: '/platform/companies',
    permission: 'platform.companies.read',
  },
  { icon: ICONS.users, label: 'Users', link: '/platform/users', permission: 'platform.users.read' },
  {
    icon: ICONS.products,
    label: 'Products',
    link: '/platform/products',
    permission: 'platform.products.read',
  },
  { icon: ICONS.plans, label: 'Plans', link: '/platform/plans', permission: 'platform.plans.read' },
  {
    icon: ICONS.subscriptions,
    label: 'Subscriptions',
    link: '/platform/subscriptions',
    permission: 'platform.subscriptions.read',
  },
  {
    icon: ICONS.invoices,
    label: 'Invoices',
    link: '/platform/invoices',
    permission: 'platform.subscriptions.read',
  },
  {
    icon: ICONS.provisioning,
    label: 'Provisioning',
    link: '/platform/provisioning',
    permission: 'platform.provisioning.read',
  },
  {
    icon: ICONS.audit,
    label: 'Audit log',
    link: '/platform/audit',
    permission: 'platform.audit.read',
  },
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
  protected readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthService);

  private readonly storage = inject(DOCUMENT).defaultView?.localStorage;
  protected readonly navOpen = signal(false);
  /** Wide screens: the menu shows icons only. Remembered in this browser. */
  protected readonly collapsed = signal(this.readCollapsed());
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

  protected readonly initials = computed(() => {
    const parts = this.userName()
      .split(/[\s@.]+/)
      .filter(Boolean);
    return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts[1]?.[0] ?? '') : '')).toUpperCase();
  });

  /** Shown on non-production hosts so nobody mistakes dev for production (derived from the host name). */
  protected readonly environment = ((): string | null => {
    const host = inject(DOCUMENT).location?.hostname ?? '';
    if (host === 'localhost' || host === '127.0.0.1') {
      return 'local';
    }
    return /(^|\.)dev\./.test(host) ? 'dev' : null;
  })();

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

  protected toggleCollapsed(): void {
    this.collapsed.update((value) => !value);
    try {
      this.storage?.setItem(NAV_KEY, String(this.collapsed()));
    } catch {
      // Storage can be blocked; the menu then keeps its state until the page is closed.
    }
  }

  private readCollapsed(): boolean {
    try {
      return this.storage?.getItem(NAV_KEY) === 'true';
    } catch {
      return false;
    }
  }

  protected signOut(): void {
    void this.auth.logout();
  }

  protected signInWithMfa(): void {
    void this.auth.login('/', { forceLogin: true });
  }
}
