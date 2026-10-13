import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from '../../core/session/session.service';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

interface Tab {
  readonly label: string;
  readonly path: string;
  readonly company: string;
  readonly platform: string;
}

const TABS: Tab[] = [
  {
    label: 'Overview',
    path: '',
    company: 'company.profile.read',
    platform: 'platform.companies.read',
  },
  {
    label: 'Products',
    path: 'products',
    company: 'company.products.launch',
    platform: 'platform.companies.read',
  },
  {
    label: 'Subscription',
    path: 'subscription',
    company: 'company.subscription.read',
    platform: 'platform.subscriptions.read',
  },
  {
    label: 'Users',
    path: 'users',
    company: 'company.users.read',
    platform: 'platform.companies.read',
  },
  {
    label: 'Usage',
    path: 'usage',
    company: 'company.usage.read',
    platform: 'platform.subscriptions.read',
  },
  {
    label: 'Audit log',
    path: 'audit',
    company: 'company.audit.read',
    platform: 'platform.audit.read',
  },
];

/** Layout of one company: header and tabs filtered by the viewer's permissions. */
@Component({
  selector: 'occpl-company-workspace',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, StatusBadge],
  providers: [CompanyContext],
  template: `
    <header class="workspace-header">
      @if (context.company(); as c) {
        <div class="row">
          <h1>{{ c.displayName }}</h1>
          <occpl-status-badge [status]="c.status" />
        </div>
        <p class="muted small">
          {{ c.legalName }} · <span class="mono">{{ c.code }}</span>
          @if (!session.membership(c.id)) {
            · viewing as OCCPL staff
          }
        </p>
      } @else {
        <h1>Company</h1>
      }
    </header>
    <nav class="tabs" aria-label="Company sections">
      @for (tab of tabs(); track tab.path) {
        <a
          [routerLink]="tab.path ? tab.path : './'"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{ exact: tab.path === '' }"
          >{{ tab.label }}</a
        >
      }
    </nav>
    <div class="workspace-body">
      @if (context.company()) {
        <router-outlet />
      } @else {
        <p role="status">Loading…</p>
      }
    </div>
  `,
  styles: `
    .workspace-header {
      margin-bottom: var(--occpl-space-3);
    }
    .workspace-header p {
      margin: var(--occpl-space-1) 0 0;
    }
    .workspace-body {
      padding-top: var(--occpl-space-4);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyWorkspace implements OnInit {
  protected readonly context = inject(CompanyContext);
  protected readonly session = inject(SessionService);
  readonly companyId = input.required<string>();

  protected readonly tabs = computed(() =>
    TABS.filter((t) => this.session.can(this.companyId(), t.company, t.platform)),
  );

  ngOnInit(): void {
    void this.context.load(this.companyId());
  }
}
