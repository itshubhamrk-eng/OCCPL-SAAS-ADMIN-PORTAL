import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Page } from '../../core/api/models';

@Component({
  selector: 'occpl-pager',
  template: `
    @if (page(); as p) {
      <nav class="row pager" aria-label="Pagination">
        <span class="muted small"
          >{{ p.totalItems }} {{ p.totalItems === 1 ? 'item' : 'items' }}</span
        >
        <span class="spacer"></span>
        @if (p.totalPages > 1) {
          <button
            type="button"
            class="btn small"
            [disabled]="p.page === 0"
            (click)="pageChange.emit(p.page - 1)"
          >
            Previous
          </button>
          <span class="small">Page {{ p.page + 1 }} of {{ p.totalPages }}</span>
          <button
            type="button"
            class="btn small"
            [disabled]="p.page + 1 >= p.totalPages"
            (click)="pageChange.emit(p.page + 1)"
          >
            Next
          </button>
        }
      </nav>
    }
  `,
  styles: `
    .pager {
      margin-top: var(--occpl-space-3);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Pager {
  readonly page = input.required<Page<unknown> | null>();
  readonly pageChange = output<number>();
}
