import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CompaniesApi } from '../../../core/api/companies.api';
import { Company, CompanyStatus, Page } from '../../../core/api/models';
import { SessionService } from '../../../core/session/session.service';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';
import { DatePipe } from '@angular/common';

@Component({
  selector: 'occpl-companies-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge, DatePipe],
  template: `
    <occpl-page-header heading="Companies" subtitle="All customer companies">
      @if (session.hasPlatform('platform.companies.manage')) {
        <a class="btn primary" routerLink="/platform/companies/new">New company</a>
      }
    </occpl-page-header>
    <form class="filters" (ngSubmit)="search(0)" role="search">
      <div class="field">
        <label for="q">Search</label>
        <input id="q" name="q" [(ngModel)]="q" placeholder="Name or code" />
      </div>
      <div class="field">
        <label for="status">Status</label>
        <select id="status" name="status" [(ngModel)]="statusFilter" (ngModelChange)="search(0)">
          <option value="">All</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s.toLowerCase() }}</option>
          }
        </select>
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Company</th>
            <th>Code</th>
            <th>Country</th>
            <th>Status</th>
            <th>Created</th>
          </tr>
        </thead>
        <tbody>
          @for (c of page()?.items ?? []; track c.id) {
            <tr>
              <td data-label="Company">
                <a [routerLink]="['/companies', c.id]">{{ c.displayName }}</a>
                <div class="muted small">{{ c.legalName }}</div>
              </td>
              <td data-label="Code" class="mono">{{ c.code }}</td>
              <td data-label="Country">{{ c.country }}</td>
              <td data-label="Status"><occpl-status-badge [status]="c.status" /></td>
              <td data-label="Created">{{ c.createdAt | date: 'mediumDate' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="muted">{{ page() ? 'No companies match.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompaniesList implements OnInit {
  private readonly api = inject(CompaniesApi);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionService);

  /** Bound from the query string (?status=ACTIVE). */
  readonly status = input<string | undefined>();
  protected readonly statuses: CompanyStatus[] = ['PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'];
  protected readonly page = signal<Page<Company> | null>(null);
  protected q = '';
  protected statusFilter = '';

  ngOnInit(): void {
    this.statusFilter = this.status() ?? '';
    this.search(0);
  }

  protected search(page: number): void {
    void this.router.navigate([], {
      queryParams: { status: this.statusFilter || null },
      replaceUrl: true,
    });
    this.api
      .search({
        q: this.q.trim() || null,
        status: (this.statusFilter || null) as CompanyStatus | null,
        page,
        size: 20,
      })
      .subscribe((p) => this.page.set(p));
  }
}
