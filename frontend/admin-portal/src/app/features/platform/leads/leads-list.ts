import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LeadsApi } from '../../../core/api/leads.api';
import { Lead, LeadStatus, Page } from '../../../core/api/models';
import { SessionService } from '../../../core/session/session.service';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-leads-list',
  imports: [FormsModule, RouterLink, DatePipe, PageHeader, Pager, StatusBadge],
  template: `
    <occpl-page-header heading="Leads" subtitle="Prospective customers and where they stand">
      @if (session.hasPlatform('platform.leads.manage')) {
        <a class="btn primary" routerLink="/platform/leads/new">Register lead</a>
      }
    </occpl-page-header>
    <form class="filters" (ngSubmit)="search(0)" role="search">
      <div class="field">
        <label for="q">Search</label>
        <input id="q" name="q" [(ngModel)]="q" placeholder="Company, contact or email" />
      </div>
      <div class="field">
        <label for="status">Status</label>
        <select id="status" name="status" [(ngModel)]="statusFilter" (ngModelChange)="search(0)">
          <option value="">All</option>
          <option value="NEW">new</option>
          <option value="CONTACTED">contacted</option>
          <option value="QUALIFIED">qualified</option>
          <option value="CONVERTED">converted</option>
          <option value="LOST">lost</option>
        </select>
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Company</th>
            <th>Contact</th>
            <th>Product interest</th>
            <th>Source</th>
            <th>Created</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          @for (l of page()?.items ?? []; track l.id) {
            <tr>
              <td data-label="Company">
                <a [routerLink]="[l.id]">{{ l.companyName }}</a>
              </td>
              <td data-label="Contact">
                {{ l.contactName }}
                <div class="muted small">{{ l.email }}</div>
              </td>
              <td data-label="Product interest">{{ l.productInterest ?? '—' }}</td>
              <td data-label="Source">{{ l.source ?? '—' }}</td>
              <td data-label="Created">{{ l.createdAt | date: 'mediumDate' }}</td>
              <td data-label="Status"><occpl-status-badge [status]="l.status" /></td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="muted">{{ page() ? 'No leads match.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadsList implements OnInit {
  private readonly api = inject(LeadsApi);
  protected readonly session = inject(SessionService);
  readonly status = input<string | undefined>();
  protected readonly page = signal<Page<Lead> | null>(null);
  protected q = '';
  protected statusFilter = '';

  ngOnInit(): void {
    this.statusFilter = this.status() ?? '';
    this.search(0);
  }

  protected search(page: number): void {
    this.api
      .search({
        q: this.q.trim() || null,
        status: (this.statusFilter || null) as LeadStatus | null,
        page,
        size: 20,
      })
      .subscribe((p) => this.page.set(p));
  }
}
