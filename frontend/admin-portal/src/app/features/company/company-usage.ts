import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { EntitlementFeature, ProductEntitlement } from '../../core/api/models';
import { SubscriptionsApi } from '../../core/api/subscriptions.api';
import { HumanizePipe } from '../../shared/ui/format.pipes';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

@Component({
  selector: 'occpl-company-usage',
  imports: [StatusBadge, DatePipe, LowerCasePipe, HumanizePipe],
  template: `
    <h2>Usage and limits</h2>
    <div class="stack">
      @for (e of entitlements(); track e.productId) {
        <section class="card">
          <div class="row">
            <h3>{{ e.productName }}</h3>
            <occpl-status-badge [status]="e.status" />
          </div>
          <div class="table-wrap">
            <table class="data">
              <thead>
                <tr>
                  <th>Feature</th>
                  <th>Usage</th>
                  <th>Limit</th>
                </tr>
              </thead>
              <tbody>
                @for (f of e.features; track f.code) {
                  <tr>
                    <td data-label="Feature">
                      {{ f.name }}
                      @if (f.resetPeriod && f.resetPeriod !== 'NONE') {
                        <div class="small muted">
                          Resets {{ f.resetPeriod | humanize | lowercase }}
                          @if (f.periodStart) {
                            , since {{ f.periodStart | date: 'mediumDate' }}
                          }
                        </div>
                      }
                    </td>
                    <td data-label="Usage">
                      @if (f.type === 'LIMIT' && f.enabled) {
                        <div>{{ f.used ?? 0 }} {{ f.unit | humanize | lowercase }}</div>
                        @if (f.limit !== null) {
                          <div
                            class="meter"
                            [class.full]="percent(f) >= 100"
                            role="meter"
                            [attr.aria-valuenow]="f.used ?? 0"
                            aria-valuemin="0"
                            [attr.aria-valuemax]="f.limit"
                            [attr.aria-label]="f.name + ' usage'"
                          >
                            <span [style.width.%]="percent(f)"></span>
                          </div>
                        }
                      } @else {
                        {{ f.enabled ? 'Included' : 'Not included' }}
                      }
                    </td>
                    <td data-label="Limit">
                      {{
                        f.type === 'LIMIT' && f.enabled
                          ? f.limit === null
                            ? 'Unlimited'
                            : f.limit
                          : '—'
                      }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      } @empty {
        <p class="muted">{{ loaded() ? 'No products yet.' : 'Loading…' }}</p>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyUsage implements OnInit {
  private readonly api = inject(SubscriptionsApi);
  private readonly context = inject(CompanyContext);
  protected readonly entitlements = signal<ProductEntitlement[]>([]);
  protected readonly loaded = signal(false);

  ngOnInit(): void {
    this.api.entitlements(this.context.id()).subscribe((e) => {
      this.entitlements.set(e);
      this.loaded.set(true);
    });
  }

  protected percent(f: EntitlementFeature): number {
    if (!f.limit) {
      return f.limit === 0 ? 100 : 0;
    }
    return Math.min(100, Math.round(((f.used ?? 0) / f.limit) * 100));
  }
}
