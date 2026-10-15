import { DatePipe, LowerCasePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { BillingApi } from '../../core/api/billing.api';
import { Plan, Subscription } from '../../core/api/models';
import { SubscriptionAction, SubscriptionsApi } from '../../core/api/subscriptions.api';
import { ActionDialog } from '../../shared/ui/action-dialog';
import { HumanizePipe, MoneyPipe } from '../../shared/ui/format.pipes';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

const LIVE = new Set(['TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED']);

/** Company subscriptions (read for company admins; assign and lifecycle actions for OCCPL staff). */
@Component({
  selector: 'occpl-company-subscription',
  imports: [
    FormsModule,
    RouterLink,
    StatusBadge,
    ActionDialog,
    DatePipe,
    LowerCasePipe,
    HumanizePipe,
    MoneyPipe,
  ],
  templateUrl: './company-subscription.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanySubscription implements OnInit {
  private readonly api = inject(SubscriptionsApi);
  private readonly billingApi = inject(BillingApi);
  protected readonly billingEnabled = signal(false);
  protected readonly invoiceNotice = signal<string | null>(null);
  protected readonly context = inject(CompanyContext);

  protected readonly subscriptions = signal<Subscription[]>([]);
  protected readonly plans = signal<Plan[]>([]);
  protected readonly loaded = signal(false);
  protected readonly live = computed(() => this.subscriptions().filter((s) => LIVE.has(s.status)));
  protected readonly past = computed(() => this.subscriptions().filter((s) => !LIVE.has(s.status)));
  protected readonly openHistory = signal<string | null>(null);
  protected readonly changing = signal<string | null>(null);
  /** Subscription the open cancel/suspend dialog applies to. */
  protected target: Subscription | null = null;

  protected assignPlanId = '';
  protected assignCurrency = '';
  protected assignTrial = false;
  protected newPlanId = '';

  protected manage(): boolean {
    return this.context.isPlatform('platform.subscriptions.manage');
  }

  ngOnInit(): void {
    this.reload();
    if (this.manage()) {
      this.api.plans({ status: 'ACTIVE', size: 100 }).subscribe((p) => this.plans.set(p.items));
      this.billingApi.status().subscribe((s) => this.billingEnabled.set(s.enabled));
    }
  }

  private reload(): void {
    this.api.forCompany(this.context.id()).subscribe((s) => {
      this.subscriptions.set(s);
      this.loaded.set(true);
    });
  }

  protected selectedPlan(): Plan | undefined {
    return this.plans().find((p) => p.id === this.assignPlanId);
  }

  protected onPlanSelected(): void {
    const plan = this.selectedPlan();
    this.assignCurrency = plan?.prices[0]?.currency ?? '';
    this.assignTrial = (plan?.trialDays ?? 0) > 0;
  }

  protected async assign(): Promise<void> {
    if (!this.assignPlanId || !this.assignCurrency) {
      return;
    }
    await firstValueFrom(
      this.api.assign(this.context.id(), this.assignPlanId, this.assignCurrency, this.assignTrial),
    );
    this.assignPlanId = '';
    this.reload();
  }

  protected async act(s: Subscription, action: SubscriptionAction): Promise<void> {
    await firstValueFrom(this.api.act(this.context.id(), s.id, action));
    this.reload();
  }

  /** Issues the invoice for the next period through the gateway, which emails the payment link. */
  protected async issueInvoice(s: Subscription): Promise<void> {
    const invoice = await firstValueFrom(this.billingApi.create(this.context.id(), s.id));
    this.invoiceNotice.set(
      `Invoice ${invoice.gatewayNumber ?? ''} issued; the payment link was emailed to the billing contact.`,
    );
  }

  protected async changePlan(s: Subscription): Promise<void> {
    if (!this.newPlanId) {
      return;
    }
    await firstValueFrom(this.api.changePlan(this.context.id(), s.id, this.newPlanId));
    this.changing.set(null);
    this.newPlanId = '';
    this.reload();
  }

  protected async cancel(mode: 'IMMEDIATE' | 'PERIOD_END', reason: string | null): Promise<void> {
    if (this.target) {
      await firstValueFrom(this.api.cancel(this.context.id(), this.target.id, mode, reason));
      this.reload();
    }
  }

  protected async suspend(reason: string | null): Promise<void> {
    if (this.target) {
      await firstValueFrom(this.api.suspend(this.context.id(), this.target.id, reason));
      this.reload();
    }
  }

  protected planName(id: string | null): string {
    return this.plans().find((p) => p.id === id)?.name ?? 'another plan';
  }

  protected otherPlans(s: Subscription): Plan[] {
    return this.plans().filter(
      (p) => p.id !== s.planId && p.prices.some((price) => price.currency === s.currency),
    );
  }
}
