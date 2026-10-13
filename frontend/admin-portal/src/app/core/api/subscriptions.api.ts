import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrlBuilder, query } from './api-url';
import {
  Page,
  Plan,
  PlanBody,
  PlanStatus,
  ProductEntitlement,
  Subscription,
  SubscriptionStatus,
} from './models';

export type SubscriptionAction = 'activate' | 'renew' | 'resume';

@Injectable({ providedIn: 'root' })
export class SubscriptionsApi {
  private readonly http = inject(HttpClient);
  private readonly api = apiUrlBuilder();

  // plans
  plans(
    criteria: { status?: PlanStatus | null; q?: string | null; page?: number; size?: number } = {},
  ): Observable<Page<Plan>> {
    return this.http.get<Page<Plan>>(this.api('/plans'), { params: query({ ...criteria }) });
  }

  plan(id: string): Observable<Plan> {
    return this.http.get<Plan>(this.api('/plans', id));
  }

  createPlan(code: string, plan: PlanBody): Observable<Plan> {
    return this.http.post<Plan>(this.api('/plans'), { code, plan });
  }

  updatePlan(id: string, plan: PlanBody): Observable<Plan> {
    return this.http.put<Plan>(this.api('/plans', id), plan);
  }

  planAction(id: string, action: 'publish' | 'archive'): Observable<Plan> {
    return this.http.post<Plan>(this.api('/plans', id, '/' + action), {});
  }

  copyPlan(id: string, code: string, name: string | null): Observable<Plan> {
    return this.http.post<Plan>(this.api('/plans', id, '/copy'), { code, name });
  }

  // subscriptions
  search(
    criteria: {
      status?: SubscriptionStatus | null;
      companyId?: string | null;
      page?: number;
      size?: number;
    } = {},
  ): Observable<Page<Subscription>> {
    return this.http.get<Page<Subscription>>(this.api('/platform/subscriptions'), {
      params: query({ ...criteria }),
    });
  }

  forCompany(companyId: string): Observable<Subscription[]> {
    return this.http.get<Subscription[]>(this.api('/companies', companyId, '/subscriptions'));
  }

  assign(
    companyId: string,
    planId: string,
    currency: string,
    startTrial: boolean,
  ): Observable<Subscription> {
    return this.http.post<Subscription>(this.api('/companies', companyId, '/subscriptions'), {
      planId,
      currency,
      startTrial,
    });
  }

  act(
    companyId: string,
    subscriptionId: string,
    action: SubscriptionAction,
  ): Observable<Subscription> {
    return this.http.post<Subscription>(
      this.api('/companies', companyId, '/subscriptions', subscriptionId, '/' + action),
      {},
    );
  }

  changePlan(companyId: string, subscriptionId: string, planId: string): Observable<Subscription> {
    return this.http.post<Subscription>(
      this.api('/companies', companyId, '/subscriptions', subscriptionId, '/change-plan'),
      { planId },
    );
  }

  cancel(
    companyId: string,
    subscriptionId: string,
    mode: 'IMMEDIATE' | 'PERIOD_END',
    reason: string | null,
  ): Observable<Subscription> {
    return this.http.post<Subscription>(
      this.api('/companies', companyId, '/subscriptions', subscriptionId, '/cancel'),
      {
        mode,
        reason,
      },
    );
  }

  suspend(
    companyId: string,
    subscriptionId: string,
    reason: string | null,
  ): Observable<Subscription> {
    return this.http.post<Subscription>(
      this.api('/companies', companyId, '/subscriptions', subscriptionId, '/suspend'),
      {
        reason,
      },
    );
  }

  entitlements(companyId: string): Observable<ProductEntitlement[]> {
    return this.http.get<ProductEntitlement[]>(this.api('/companies', companyId, '/entitlements'));
  }
}
