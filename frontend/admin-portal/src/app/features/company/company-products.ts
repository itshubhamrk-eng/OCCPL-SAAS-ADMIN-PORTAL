import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CompanyProduct } from '../../core/api/models';
import { PlatformApi } from '../../core/api/platform.api';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

/** Product launcher: entitled products, with an Open button once the product is ready. */
@Component({
  selector: 'occpl-company-products',
  imports: [StatusBadge, DatePipe],
  template: `
    <h2>Products</h2>
    <div class="grid">
      @for (p of products(); track p.productId) {
        <article class="card product">
          <h3>{{ p.productName }}</h3>
          <div class="row">
            <occpl-status-badge [status]="p.entitlementStatus" />
            @if (p.provisioningState && p.provisioningState !== 'PROVISIONED') {
              <occpl-status-badge [status]="p.provisioningState" />
            }
          </div>
          <p class="muted small">
            @if (p.validUntil) {
              Access until {{ p.validUntil | date: 'mediumDate' }}.
            }
            {{ message(p) }}
          </p>
          @if (p.launchUrl) {
            <a
              class="btn primary"
              [href]="p.launchUrl"
              [attr.target]="p.openInNewTab ? '_blank' : null"
              [attr.rel]="p.openInNewTab ? 'noopener noreferrer' : null"
              >Open {{ p.productName }}</a
            >
          }
        </article>
      } @empty {
        <p class="muted">
          {{
            loaded()
              ? 'This company has no products yet. Products come with a subscription plan.'
              : 'Loading…'
          }}
        </p>
      }
    </div>
  `,
  styles: `
    .product {
      display: flex;
      flex-direction: column;
      gap: var(--occpl-space-2);
      align-items: flex-start;
    }
    .product h3 {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyProducts implements OnInit {
  private readonly api = inject(PlatformApi);
  private readonly context = inject(CompanyContext);
  protected readonly products = signal<CompanyProduct[]>([]);
  protected readonly loaded = signal(false);

  ngOnInit(): void {
    this.api.companyProducts(this.context.id()).subscribe((p) => {
      this.products.set(p);
      this.loaded.set(true);
    });
  }

  protected message(p: CompanyProduct): string {
    if (p.launchUrl) {
      return 'Ready to use.';
    }
    if (p.entitlementStatus === 'SUSPENDED') {
      return 'Access is suspended until the subscription is settled.';
    }
    if (this.context.company()?.status !== 'ACTIVE') {
      return 'Products can be used once the company is active.';
    }
    switch (p.provisioningState) {
      case 'PROVISIONING':
      case null:
        return 'Being set up — this usually takes a few seconds.';
      case 'FAILED':
        return 'Set-up failed; OCCPL has been notified.';
      default:
        return `Status: ${p.provisioningState.toLowerCase().replace(/_/g, ' ')}.`;
    }
  }
}
