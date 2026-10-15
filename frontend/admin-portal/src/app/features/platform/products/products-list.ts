import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CatalogApi } from '../../../core/api/catalog.api';
import { Page, Product, ProductStatus } from '../../../core/api/models';
import { SessionService } from '../../../core/session/session.service';
import { PageHeader } from '../../../shared/ui/page-header';
import { Pager } from '../../../shared/ui/pager';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-products-list',
  imports: [FormsModule, RouterLink, PageHeader, Pager, StatusBadge],
  template: `
    <occpl-page-header
      heading="Products"
      subtitle="Product services that companies can subscribe to"
    >
      @if (session.hasPlatform('platform.products.manage')) {
        <a class="btn primary" routerLink="/platform/products/new">Register product</a>
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
          <option value="DRAFT">draft</option>
          <option value="ACTIVE">active</option>
          <option value="RETIRED">retired</option>
        </select>
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>Product</th>
            <th>Code</th>
            <th>Configuration</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          @for (p of page()?.items ?? []; track p.id) {
            <tr>
              <td data-label="Product">
                <a [routerLink]="[p.id]">{{ p.name }}</a>
                <div class="muted small">{{ p.appUrl }}</div>
              </td>
              <td data-label="Code" class="mono">{{ p.code }}</td>
              <td data-label="Configuration">
                {{ p.currentVersionNo ? 'Version ' + p.currentVersionNo : 'Not published' }}
              </td>
              <td data-label="Status"><occpl-status-badge [status]="p.status" /></td>
            </tr>
          } @empty {
            <tr>
              <td colspan="4" class="muted">{{ page() ? 'No products match.' : 'Loading…' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <occpl-pager [page]="page()" (pageChange)="search($event)" />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductsList implements OnInit {
  private readonly api = inject(CatalogApi);
  protected readonly session = inject(SessionService);
  readonly status = input<string | undefined>();
  protected readonly page = signal<Page<Product> | null>(null);
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
        status: (this.statusFilter || null) as ProductStatus | null,
        page,
        size: 20,
      })
      .subscribe((p) => this.page.set(p));
  }
}
