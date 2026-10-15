import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
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
  readonly color: 'crimson' | 'indigo' | 'sky' | 'amber' | 'teal' | 'plum';
}

interface Slice {
  readonly label: string;
  readonly value: number;
  readonly color: string;
}

/** Platform overview built from list totals (each tile needs the matching read permission). */
@Component({
  selector: 'occpl-platform-dashboard',
  imports: [RouterLink, PageHeader],
  template: `
    <occpl-page-header
      [heading]="greeting()"
      subtitle="Current state of companies, subscriptions and provisioning"
    />
    @if (donut(); as chart) {
      <section class="card overview" aria-label="Companies by status">
        <div
          class="donut"
          [style.background]="chart.gradient"
          role="img"
          [attr.aria-label]="chart.summary"
        >
          <div class="hole">
            <strong>{{ chart.total }}</strong>
            <span class="muted small">companies</span>
          </div>
        </div>
        <ul class="legend">
          @for (slice of chart.slices; track slice.label) {
            <li>
              <span class="swatch" [style.background]="slice.color"></span>
              {{ slice.label }} <strong>{{ slice.value }}</strong>
            </li>
          }
        </ul>
      </section>
    }
    <div class="grid">
      @for (tile of tiles(); track tile.label) {
        <a
          class="card tile"
          [routerLink]="tile.link"
          [queryParams]="tile.query ?? null"
          [class]="'card tile ' + tile.color"
        >
          <span class="label"><span class="chip"></span>{{ tile.label }}</span>
          <span class="stat">{{ tile.value ?? '—' }}</span>
        </a>
      }
    </div>
    @if (tiles().length === 0 && loaded()) {
      <p class="muted">Your platform role has no dashboard information.</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .overview {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--occpl-space-6);
      margin-bottom: var(--occpl-space-4);
      border-top: 5px solid var(--occpl-color-primary);
    }
    .donut {
      display: grid;
      place-items: center;
      width: 9.5rem;
      height: 9.5rem;
      border-radius: 50%;
      flex: none;
    }
    .hole {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      width: 6.25rem;
      height: 6.25rem;
      border-radius: 50%;
      background: var(--occpl-color-surface);
    }
    .hole strong {
      font-size: 1.75rem;
      line-height: 1;
    }
    .legend {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--occpl-space-2);
    }
    .swatch {
      display: inline-block;
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 4px;
      margin-right: var(--occpl-space-2);
    }
    .tile {
      --tone: var(--occpl-color-primary);
      display: flex;
      flex-direction: column;
      gap: var(--occpl-space-2);
      color: inherit;
      text-decoration: none;
      border-left: 5px solid var(--tone);
      background: linear-gradient(
        150deg,
        color-mix(in srgb, var(--tone) 14%, transparent) 0%,
        var(--occpl-color-surface-glass) 70%
      );
    }
    .tile:hover {
      box-shadow: var(--occpl-shadow-md);
    }
    .label {
      display: flex;
      align-items: center;
      gap: var(--occpl-space-2);
      font-size: var(--occpl-font-size-sm);
      color: var(--occpl-color-text-muted);
    }
    .chip {
      width: 1.5rem;
      height: 1.5rem;
      border-radius: 8px;
      background: var(--tone);
    }
    .stat {
      color: var(--tone);
    }
    .crimson {
      --tone: var(--occpl-color-primary);
    }
    .indigo {
      --tone: var(--occpl-color-accent);
    }
    .sky {
      --tone: var(--occpl-color-sky);
    }
    .amber {
      --tone: var(--occpl-color-amber);
    }
    .teal {
      --tone: var(--occpl-color-teal);
    }
    .plum {
      --tone: var(--occpl-color-plum);
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

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';
    const me = this.session.me();
    const name = (me?.displayName || '').split(' ')[0];
    return name ? `Good ${part}, ${name}` : `Good ${part}`;
  });

  /** Companies by status, drawn from the same totals as the tiles (only when the user may read companies). */
  protected readonly donut = computed(() => {
    const value = (label: string) => this.tiles().find((t) => t.label === label)?.value ?? 0;
    const slices: Slice[] = [
      { label: 'Active', value: value('Active companies'), color: 'var(--occpl-color-primary)' },
      { label: 'Pending', value: value('Pending companies'), color: 'var(--occpl-color-amber)' },
      {
        label: 'Suspended',
        value: value('Suspended companies'),
        color: 'var(--occpl-color-accent)',
      },
    ];
    const total = slices.reduce((sum, slice) => sum + slice.value, 0);
    if (total === 0) {
      return null;
    }
    let from = 0;
    const stops = slices.map((slice) => {
      const start = from;
      from += (slice.value / total) * 100;
      return `${slice.color} ${start}% ${from}%`;
    });
    return {
      total,
      slices,
      gradient: `conic-gradient(${stops.join(', ')})`,
      summary: slices.map((slice) => `${slice.label} ${slice.value}`).join(', '),
    };
  });

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
      {
        label: 'Active companies',
        link: '/platform/companies',
        query: { status: 'ACTIVE' },
        color: 'crimson',
      },
      total(this.companies.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.companies.read',
      {
        label: 'Pending companies',
        link: '/platform/companies',
        query: { status: 'PENDING' },
        color: 'amber',
      },
      total(this.companies.search({ status: 'PENDING', size: 1 })),
    );
    add(
      'platform.companies.read',
      {
        label: 'Suspended companies',
        link: '/platform/companies',
        query: { status: 'SUSPENDED' },
        color: 'plum',
      },
      total(this.companies.search({ status: 'SUSPENDED', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      {
        label: 'Active subscriptions',
        link: '/platform/subscriptions',
        query: { status: 'ACTIVE' },
        color: 'indigo',
      },
      total(this.subscriptions.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      {
        label: 'Trials',
        link: '/platform/subscriptions',
        query: { status: 'TRIAL' },
        color: 'sky',
      },
      total(this.subscriptions.search({ status: 'TRIAL', size: 1 })),
    );
    add(
      'platform.subscriptions.read',
      {
        label: 'Past due',
        link: '/platform/subscriptions',
        query: { status: 'PAST_DUE' },
        color: 'amber',
      },
      total(this.subscriptions.search({ status: 'PAST_DUE', size: 1 })),
    );
    add(
      'platform.products.read',
      {
        label: 'Active products',
        link: '/platform/products',
        query: { status: 'ACTIVE' },
        color: 'teal',
      },
      total(this.catalog.search({ status: 'ACTIVE', size: 1 })),
    );
    add(
      'platform.provisioning.read',
      {
        label: 'Failed provisioning jobs',
        link: '/platform/provisioning',
        query: { status: 'FAILED' },
        color: 'crimson',
      },
      total(this.platform.jobs({ status: 'FAILED', size: 1 })),
    );
    this.tiles.set(await Promise.all(requests));
    this.loaded.set(true);
  }
}
