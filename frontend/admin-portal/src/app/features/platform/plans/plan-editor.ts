import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CatalogApi } from '../../../core/api/catalog.api';
import { BillingPeriod, Plan, PlanBody, Product, ProductFeature } from '../../../core/api/models';
import { SubscriptionsApi } from '../../../core/api/subscriptions.api';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { HumanizePipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';

type FeatureForm = FormGroup<{
  code: FormControl<string>;
  name: FormControl<string>;
  type: FormControl<string>;
  unit: FormControl<string>;
  included: FormControl<boolean>;
  unlimited: FormControl<boolean>;
  limit: FormControl<number | null>;
}>;

/** Creates a plan or edits a draft. Prices are entered in major units (rupees) and sent in minor units. */
@Component({
  selector: 'occpl-plan-editor',
  imports: [ReactiveFormsModule, PageHeader, FieldError, HumanizePipe],
  templateUrl: './plan-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanEditor implements OnInit {
  private readonly subscriptions = inject(SubscriptionsApi);
  private readonly catalog = inject(CatalogApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  // Form arrays are not signals: after async changes the view must be told to render (zoneless).
  private readonly cdr = inject(ChangeDetectorRef);

  /** Set when editing a draft (route /platform/plans/:planId/edit). */
  readonly planId = input<string | undefined>();

  protected readonly periods: BillingPeriod[] = ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'];
  protected readonly activeProducts = signal<Product[]>([]);
  protected readonly formErrors = signal<string[]>([]);
  protected readonly saving = signal(false);
  protected productToAdd = '';

  protected readonly form = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9-]{1,63}$/)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', Validators.maxLength(2000)],
    billingPeriod: ['MONTHLY' as BillingPeriod, Validators.required],
    trialDays: [0, [Validators.required, Validators.min(0), Validators.max(365)]],
    prices: this.fb.array([this.priceGroup('INR', null)]),
    products: this.fb.array<FormGroup>([]),
  });

  get prices(): FormArray {
    return this.form.controls.prices;
  }

  get products(): FormArray<FormGroup> {
    return this.form.controls.products;
  }

  featuresOf(product: FormGroup): FormArray<FeatureForm> {
    return product.get('features') as FormArray<FeatureForm>;
  }

  async ngOnInit(): Promise<void> {
    this.catalog
      .search({ status: 'ACTIVE', size: 100 })
      .subscribe((p) => this.activeProducts.set(p.items));
    const id = this.planId();
    if (id) {
      const plan = await firstValueFrom(this.subscriptions.plan(id));
      await this.fill(plan);
    }
  }

  protected priceGroup(currency: string, amountMinor: number | null): FormGroup {
    return this.fb.group({
      currency: [currency, [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]],
      amount: [
        amountMinor === null ? null : amountMinor / 100,
        [Validators.required, Validators.min(0)],
      ],
    });
  }

  protected addPrice(): void {
    this.prices.push(this.priceGroup('', null));
  }

  protected removePrice(index: number): void {
    this.prices.removeAt(index);
  }

  protected available(): Product[] {
    const chosen = new Set(this.products.controls.map((c) => c.get('productId')?.value as string));
    return this.activeProducts().filter((p) => !chosen.has(p.id));
  }

  protected async addProduct(): Promise<void> {
    const product = this.activeProducts().find((p) => p.id === this.productToAdd);
    if (!product) {
      return;
    }
    const features = await firstValueFrom(this.catalog.features(product.id));
    this.products.push(this.productGroup(product.id, product.name, features, null));
    this.productToAdd = '';
    this.cdr.markForCheck();
  }

  protected removeProduct(index: number): void {
    this.products.removeAt(index);
  }

  private productGroup(
    productId: string,
    name: string,
    features: ProductFeature[],
    plan: Plan | null,
  ): FormGroup {
    const granted = new Map(
      (plan?.products.find((p) => p.productId === productId)?.features ?? []).map((f) => [
        f.code,
        f,
      ]),
    );
    return this.fb.group({
      productId: [productId],
      productName: [name],
      features: this.fb.array(
        features
          .filter((f) => f.status === 'ACTIVE' || granted.has(f.code))
          .map((f) => {
            const grant = granted.get(f.code);
            return this.fb.group({
              code: [f.code],
              name: [f.name],
              type: [f.type as string],
              unit: [(f.unit ?? '') as string],
              included: [grant ? grant.enabled : false],
              unlimited: [grant ? grant.enabled && grant.limit === null : false],
              limit: [grant?.limit ?? (null as number | null), [Validators.min(0)]],
            }) as FeatureForm;
          }),
      ),
    });
  }

  private async fill(plan: Plan): Promise<void> {
    this.form.patchValue({
      code: plan.code,
      name: plan.name,
      description: plan.description ?? '',
      billingPeriod: plan.billingPeriod,
      trialDays: plan.trialDays,
    });
    this.form.controls.code.disable();
    this.prices.clear();
    plan.prices.forEach((p) => this.prices.push(this.priceGroup(p.currency, p.amountMinor)));
    for (const product of plan.products) {
      const features = await firstValueFrom(this.catalog.features(product.productId));
      this.products.push(this.productGroup(product.productId, product.productName, features, plan));
    }
    this.cdr.markForCheck();
  }

  /** Plan body for the API; null with an error message when a counted feature has no limit. */
  protected body(): PlanBody | null {
    const v = this.form.getRawValue();
    let missingLimit = false;
    const body: PlanBody = {
      name: v.name.trim(),
      description: v.description.trim() || null,
      billingPeriod: v.billingPeriod,
      trialDays: Number(v.trialDays),
      prices: (v.prices as { currency: string; amount: number }[]).map((p) => ({
        currency: p.currency.toUpperCase(),
        amountMinor: Math.round(Number(p.amount) * 100),
      })),
      products: (
        v.products as {
          productId: string;
          features: {
            code: string;
            type: string;
            included: boolean;
            unlimited: boolean;
            limit: number | null;
          }[];
        }[]
      ).map((p) => ({
        productId: p.productId,
        features: p.features
          .filter((f) => f.included)
          .map((f) => {
            if (f.type !== 'LIMIT') {
              return { code: f.code, enabled: true };
            }
            if (
              !f.unlimited &&
              (f.limit === null || f.limit === undefined || `${f.limit}` === '')
            ) {
              missingLimit = true;
            }
            return { code: f.code, enabled: true, limit: f.unlimited ? null : Number(f.limit) };
          }),
      })),
    };
    if (missingLimit) {
      this.formErrors.set([
        'Enter a limit for every included counted feature, or tick "Unlimited".',
      ]);
      return null;
    }
    return body;
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    this.formErrors.set([]);
    if (this.form.invalid) {
      return;
    }
    const body = this.body();
    if (!body) {
      return;
    }
    this.saving.set(true);
    try {
      const id = this.planId();
      const plan = id
        ? await firstValueFrom(this.subscriptions.updatePlan(id, body))
        : await firstValueFrom(this.subscriptions.createPlan(this.form.controls.code.value, body));
      await this.router.navigate(['/platform/plans', plan.id]);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
