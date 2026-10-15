import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { CatalogApi } from '../../../core/api/catalog.api';
import {
  FeatureType,
  FeatureUnit,
  Product,
  ProductFeature,
  ProductVersion,
  ResetPeriod,
} from '../../../core/api/models';
import { SessionService } from '../../../core/session/session.service';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { ActionDialog } from '../../../shared/ui/action-dialog';
import { HumanizePipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';
import { ProductFields } from './product-fields';
import { AUDIENCE_PATTERN } from './product-register';

const CONFIG_TEMPLATE = {
  schemaVersion: 1,
  launch: { path: '/companies/{companyId}', openInNewTab: true },
  provisioning: {
    mode: 'SYNC',
    path: '/provisioning/v1/tenants/{companyId}',
    timeoutSeconds: 10,
    maxAttempts: 5,
  },
};

function jsonValidator(control: AbstractControl): ValidationErrors | null {
  try {
    JSON.parse(control.value as string);
    return null;
  } catch (e) {
    return { json: `Not valid JSON: ${(e as Error).message}` };
  }
}

@Component({
  selector: 'occpl-product-detail',
  imports: [
    ReactiveFormsModule,
    PageHeader,
    StatusBadge,
    ActionDialog,
    FieldError,
    ProductFields,
    DatePipe,
    JsonPipe,
    HumanizePipe,
  ],
  templateUrl: './product-detail.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductDetail implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly session = inject(SessionService);

  readonly productId = input.required<string>();
  protected readonly product = signal<Product | null>(null);
  protected readonly versions = signal<ProductVersion[]>([]);
  protected readonly features = signal<ProductFeature[]>([]);
  protected readonly openVersion = signal<number | null>(null);
  protected readonly editErrors = signal<string[]>([]);
  protected readonly versionErrors = signal<string[]>([]);
  protected readonly featureErrors = signal<string[]>([]);
  protected readonly editing = signal(false);
  protected readonly creatingVersion = signal(false);

  protected readonly units: FeatureUnit[] = [
    'SEATS',
    'STORAGE_GB',
    'RECORDS',
    'API_CALLS',
    'TRANSACTIONS',
    'DOCUMENTS',
    'MESSAGES',
    'COUNT',
  ];
  protected readonly periods: ResetPeriod[] = ['NONE', 'DAILY', 'MONTHLY', 'BILLING_PERIOD'];

  protected readonly editForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    appUrl: ['', [Validators.required, Validators.maxLength(500)]],
    apiBaseUrl: ['', Validators.maxLength(500)],
    apiAudience: ['', [Validators.required, Validators.pattern(AUDIENCE_PATTERN)]],
    serviceClientId: ['', Validators.pattern(AUDIENCE_PATTERN)],
    description: ['', Validators.maxLength(2000)],
  });

  protected readonly versionForm = this.fb.group({
    config: ['', [Validators.required, jsonValidator]],
    notes: ['', Validators.maxLength(1000)],
  });

  protected readonly featureForm = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9_.-]{1,63}$/)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    type: ['LIMIT' as FeatureType, Validators.required],
    unit: ['COUNT' as FeatureUnit],
    resetPeriod: ['MONTHLY' as ResetPeriod],
    description: ['', Validators.maxLength(1000)],
  });

  protected canManage(): boolean {
    return this.session.hasPlatform('platform.products.manage');
  }

  async ngOnInit(): Promise<void> {
    this.product.set(await firstValueFrom(this.api.get(this.productId())));
    this.loadVersions();
    this.api.features(this.productId()).subscribe((f) => this.features.set(f));
  }

  private loadVersions(): void {
    this.api.versions(this.productId()).subscribe((v) => this.versions.set(v));
  }

  // ------------------------------------------------------------ details

  protected startEdit(): void {
    const p = this.product();
    if (!p) {
      return;
    }
    this.editForm.setValue({
      name: p.name,
      appUrl: p.appUrl,
      apiBaseUrl: p.apiBaseUrl ?? '',
      apiAudience: p.apiAudience,
      serviceClientId: p.serviceClientId ?? '',
      description: p.description ?? '',
    });
    this.editErrors.set([]);
    this.editing.set(true);
  }

  protected async saveEdit(): Promise<void> {
    const p = this.product();
    this.editForm.markAllAsTouched();
    if (!p || this.editForm.invalid) {
      return;
    }
    const v = this.editForm.getRawValue();
    try {
      this.product.set(
        await firstValueFrom(
          this.api.update(p, {
            name: v.name.trim(),
            appUrl: v.appUrl.trim(),
            apiBaseUrl: v.apiBaseUrl.trim(),
            apiAudience: v.apiAudience.trim(),
            serviceClientId: v.serviceClientId.trim(),
            description: v.description.trim(),
          }),
        ),
      );
      this.editing.set(false);
    } catch (e) {
      this.editErrors.set(applyServerErrors(this.editForm, e));
    }
  }

  protected async changeStatus(
    action: 'activate' | 'retire',
    reason: string | null,
  ): Promise<void> {
    this.product.set(await firstValueFrom(this.api.changeStatus(this.productId(), action, reason)));
  }

  // ------------------------------------------------------------ versions

  protected startVersion(): void {
    const current = this.versions().find((v) => v.current);
    const base = current ? this.api.version(this.productId(), current.versionNo) : null;
    this.versionErrors.set([]);
    this.versionForm.reset({ config: JSON.stringify(CONFIG_TEMPLATE, null, 2), notes: '' });
    if (base) {
      base.subscribe((v) =>
        this.versionForm.controls.config.setValue(JSON.stringify(v.config, null, 2)),
      );
    }
    this.creatingVersion.set(true);
  }

  protected async createVersion(): Promise<void> {
    this.versionForm.markAllAsTouched();
    if (this.versionForm.invalid) {
      return;
    }
    const v = this.versionForm.getRawValue();
    try {
      await firstValueFrom(
        this.api.createVersion(this.productId(), JSON.parse(v.config), v.notes.trim() || null),
      );
      this.creatingVersion.set(false);
      this.loadVersions();
    } catch (e) {
      // Schema violations come back with JSON-pointer-like field names; show them all above the editor.
      this.versionErrors.set(applyServerErrors(this.versionForm, e));
    }
  }

  protected toggleVersion(versionNo: number): void {
    if (this.openVersion() === versionNo) {
      this.openVersion.set(null);
      return;
    }
    this.api.version(this.productId(), versionNo).subscribe((full) => {
      this.versions.update((list) => list.map((v) => (v.versionNo === versionNo ? full : v)));
      this.openVersion.set(versionNo);
    });
  }

  protected async publish(versionNo: number): Promise<void> {
    await firstValueFrom(this.api.publishVersion(this.productId(), versionNo));
    this.product.set(await firstValueFrom(this.api.get(this.productId())));
    this.loadVersions();
  }

  protected async deleteDraft(versionNo: number): Promise<void> {
    await firstValueFrom(this.api.deleteVersion(this.productId(), versionNo));
    this.loadVersions();
  }

  // ------------------------------------------------------------ features

  protected async addFeature(): Promise<void> {
    this.featureForm.markAllAsTouched();
    if (this.featureForm.invalid) {
      return;
    }
    const v = this.featureForm.getRawValue();
    const limit = v.type === 'LIMIT';
    try {
      const feature = await firstValueFrom(
        this.api.addFeature(this.productId(), {
          code: v.code,
          name: v.name.trim(),
          description: v.description.trim() || null,
          type: v.type,
          unit: limit ? v.unit : null,
          resetPeriod: limit ? v.resetPeriod : null,
        }),
      );
      this.features.update((list) => [...list, feature]);
      this.featureForm.reset({
        type: 'LIMIT',
        unit: 'COUNT',
        resetPeriod: 'MONTHLY',
        code: '',
        name: '',
        description: '',
      });
      this.featureErrors.set([]);
    } catch (e) {
      this.featureErrors.set(applyServerErrors(this.featureForm, e));
    }
  }

  protected async setFeatureStatus(
    feature: ProductFeature,
    action: 'deprecate' | 'reactivate',
  ): Promise<void> {
    const updated = await firstValueFrom(
      this.api.changeFeatureStatus(this.productId(), feature.id, action),
    );
    this.features.update((list) => list.map((f) => (f.id === updated.id ? updated : f)));
  }
}
