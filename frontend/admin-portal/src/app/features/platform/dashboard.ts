import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, catchError, firstValueFrom, map, of } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog.api';
import { CompaniesApi } from '../../core/api/companies.api';
import { PlatformApi } from '../../core/api/platform.api';
import { SubscriptionsApi } from '../../core/api/subscriptions.api';
import { SessionService } from '../../core/session/session.service';
import { Company, ProvisioningJob } from '../../core/api/models';
import { StatusBadge } from '../../shared/ui/status-badge';

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
  imports: [RouterLink, DatePipe, StatusBadge],
  template: `
    <header class="hello">
      <h1>
        {{ greeting().text }}
        @if (greeting().name) {
          , <span class="name">{{ greeting().name }}</span>
        }
      </h1>
      <p class="muted subtitle">
        Here is the current state of companies, subscriptions and provisioning
      </p>
    </header>
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
    @if (recent().length > 0 || jobs().length > 0) {
      <div class="lists">
        @if (recent().length > 0) {
          <section class="card panel crimson-top" aria-label="Recent companies">
            <div class="panel-head">
              <h2>Recent companies</h2>
              <a routerLink="/platform/companies">View all</a>
            </div>
            @for (company of recent(); track company.id) {
              <a class="row-item" [routerLink]="['/companies', company.id]">
                <strong>{{ company.displayName }}</strong>
                <occpl-status-badge [status]="company.status" />
                <span class="muted">{{ company.createdAt | date: 'dd MMM yyyy' }}</span>
              </a>
            }
          </section>
        }
        @if (jobs().length > 0) {
          <section class="card panel indigo-top" aria-label="Provisioning">
            <div class="panel-head">
              <h2>Provisioning</h2>
              <a routerLink="/platform/provisioning">View all</a>
            </div>
            @for (job of jobs(); track job.id) {
              <a class="row-item job" routerLink="/platform/provisioning">
                <span class="dot" [class]="'dot ' + job.status.toLowerCase()"></span>
                <span class="job-text">
                  <strong
                    >{{ job.operation === 'PROVISION' ? 'Provision' : 'Deprovision' }}
                    {{ job.productCode }}</strong
                  >
                  <span class="muted small">
                    {{ job.companyCode }} · {{ jobStatus(job) }} ·
                    {{ job.updatedAt | date: 'dd MMM, HH:mm' }}
                  </span>
                </span>
              </a>
            }
          </section>
        }
      </div>
    }
    @if (tiles().length === 0 && loaded()) {
      <p class="muted">Your platform role has no dashboard information.</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .hello {
      margin-bottom: var(--occpl-space-6);
    }
    .hello h1 {
      font-size: 2.375rem;
      letter-spacing: -0.03em;
    }
    .name {
      color: var(--occpl-color-primary);
    }
    .subtitle {
      margin: var(--occpl-space-1) 0 0;
      font-size: 1.0625rem;
    }
    .lists {
      display: flex;
      flex-wrap: wrap;
      gap: var(--occpl-space-4);
      margin-top: var(--occpl-space-4);
      align-items: flex-start;
    }
    .panel {
      flex: 1 1 18rem;
      min-width: 0;
    }
    .panel:first-child {
      flex-grow: 3;
    }
    .crimson-top {
      border-top: 5px solid var(--occpl-color-primary);
    }
    .indigo-top {
      border-top: 5px solid var(--occpl-color-accent);
    }
    .panel-head {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      margin-bottom: var(--occpl-space-2);
    }
    .panel-head h2 {
      margin: 0;
    }
    .panel-head a {
      font-weight: 600;
    }
    .row-item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--occpl-space-2) var(--occpl-space-4);
      padding: var(--occpl-space-3) 0;
      border-top: 1px solid var(--occpl-color-border);
      color: inherit;
      text-decoration: none;
    }
    .row-item strong:first-child {
      flex: 2 1 9rem;
    }
    .job {
      flex-wrap: nowrap;
      align-items: flex-start;
    }
    .job-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .dot {
      flex: none;
      width: 0.625rem;
      height: 0.625rem;
      margin-top: 0.4rem;
      border-radius: 50%;
      background: var(--occpl-color-text-muted);
    }
    .dot.succeeded {
      background: var(--occpl-color-teal);
    }
    .dot.failed {
      background: var(--occpl-color-primary);
    }
    .dot.running,
    .dot.pending,
    .dot.awaiting_callback {
      background: var(--occpl-color-amber);
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
  protected readonly recent = signal<Company[]>([]);
  protected readonly jobs = signal<ProvisioningJob[]>([]);

  protected jobStatus(job: ProvisioningJob): string {
    return job.status === 'FAILED'
      ? `failed, attempt ${job.attempts} of ${job.maxAttempts}`
      : job.status.toLowerCase().replace('_', ' ');
  }

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';
    const me = this.session.me();
    const name = (me?.displayName || '').split(' ')[0];
    return { text: `Good ${part}`, name };
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
    if (this.session.hasPlatform('platform.companies.read')) {
      this.companies
        .search({ size: 5 })
        .pipe(catchError(() => of(null)))
        .subscribe((page) => this.recent.set(page?.items ?? []));
    }
    if (this.session.hasPlatform('platform.provisioning.read')) {
      this.platform
        .jobs({ size: 5 })
        .pipe(catchError(() => of(null)))
        .subscribe((page) => this.jobs.set(page?.items ?? []));
    }
    this.tiles.set(await Promise.all(requests));
    this.loaded.set(true);
  }
}
