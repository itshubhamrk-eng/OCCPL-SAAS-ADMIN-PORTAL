import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, catchError, firstValueFrom, map, of } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog.api';
import { CompaniesApi } from '../../core/api/companies.api';
import { PlatformApi } from '../../core/api/platform.api';
import { SubscriptionsApi } from '../../core/api/subscriptions.api';
import { SessionService } from '../../core/session/session.service';
import { PageHeader } from '../../shared/ui/page-header';

interface Tile {
  readonly label: string;
  readonly value: number | null;
  readonly link: string;
  readonly query?: Record<string, string>;
  readonly tone?: 'warning' | 'danger';
}

/** Platform overview built from list totals (each tile needs the matching read permission). */
@Component({
  selector: 'occpl-platform-dashboard',
  imports: [RouterLink, PageHeader],
  template: `
    <occpl-page-header
      heading="Platform dashboard"
      subtitle="Current state of companies, subscriptions and provisioning"
    />
    <div class="grid">
      @for (tile of tiles(); track tile.label) {
        <a
          class="card tile"
          [routerLink]="tile.link"
          [queryParams]="tile.query ?? null"
          [class]="'card tile ' + (tile.value ? (tile.tone ?? '') : '')"
        >
          <span class="muted small">{{ tile.label }}</span>
          <span class="stat">{{ tile.value ?? '—' }}</span>
        </a>
      }
    </div>
    @if (tiles().length === 0 && loaded()) {
      <p class="muted">Your platform role has no dashboard information.</p>
    }
  `,
  styles: `
    .tile {
      display: flex;
      flex-direction: column;
      gap: var(--occpl-space-1);
      color: inherit;
      text-decoration: none;
    }
    .tile:hover {
      border-color: var(--occpl-color-primary);
    }
    .warning .stat {
      color: var(--occpl-color-warning);
    }
    .danger .stat {
      color: var(--occpl-color-danger);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformDashboard implements OnInit {
  private readonly session = inject(SessionService);
  private readonly companies = inject(CompaniesApi);
  private readonly subscriptions = inject(SubscriptionsApi);
  private readonly catalog = inject(CatalogApi);
  private readonly platform = inject(PlatformApi);

  protected readonly tiles = signal<Tile[]>([]);
  protected readonly loaded = signal(false);

  async ngOnInit(): Promise<void> {
    const requests: Promise<Tile>[] = [];
    const add = (permission: string, tile: Omit<Tile, 'value'>, total: Observable<number>) => {
      if (this.session.hasPlatform(permission)) {
        requests.push(
          firstValueFrom(
            total.pipe(
              catchError(() => of(null)),
              map((value) => ({ ...tile, value })),
            ),
          ),
        );
      }
    };
    const total = <T>(page: Observable<{ totalItems: number } & T>) =>
      page.pipe(map((p) => p.totalItems));
    add(
      'platform.companies.read',
      { label: 'Active companies', link: '/platform/companies', query: { status: 'ACTIVE' } },
      total(this.companies.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.companies.read',
      { label: 'Pending companies', link: '/platform/companies', query: { status: 'PENDING' } },
      total(this.companies.search({ status: 'PENDING', size: 1 })),
    );
    add(
      'platform.companies.read',
      {
        label: 'Suspended companies',
        link: '/platform/companies',
        query: { status: 'SUSPENDED' },
        tone: 'warning',
      },
      total(this.companies.search({ status: 'SUSPENDED', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      {
        label: 'Active subscriptions',
        link: '/platform/subscriptions',
        query: { status: 'ACTIVE' },
      },
      total(this.subscriptions.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      { label: 'Trials', link: '/platform/subscriptions', query: { status: 'TRIAL' } },
      total(this.subscriptions.search({ status: 'TRIAL', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      {
        label: 'Past due',
        link: '/platform/subscriptions',
        query: { status: 'PAST_DUE' },
        tone: 'warning',
      },
      total(this.subscriptions.search({ status: 'PAST_DUE', size: 1 })),
    );
    add(
      'platform.products.read',
      { label: 'Active products', link: '/platform/products', query: { status: 'ACTIVE' } },
      total(this.catalog.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.provisioning.read',
      {
        label: 'Failed provisioning jobs',
        link: '/platform/provisioning',
        query: { status: 'FAILED' },
        tone: 'danger',
      },
      total(this.platform.jobs({ status: 'FAILED', size: 1 })),
    );
    this.tiles.set(await Promise.all(requests));
    this.loaded.set(true);
  }
}
