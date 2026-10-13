import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CatalogApi } from '../../../core/api/catalog.api';
import { SubscriptionsApi } from '../../../core/api/subscriptions.api';
import { PlanEditor } from './plan-editor';

const PRODUCT = { id: 'p-1', name: 'Notes', status: 'ACTIVE' };
const FEATURES = [
  {
    id: 'f-1',
    code: 'notes',
    name: 'Notes',
    type: 'LIMIT',
    unit: 'RECORDS',
    resetPeriod: 'NONE',
    status: 'ACTIVE',
  },
  {
    id: 'f-2',
    code: 'export',
    name: 'Export',
    type: 'BOOLEAN',
    unit: null,
    resetPeriod: null,
    status: 'ACTIVE',
  },
  {
    id: 'f-3',
    code: 'old',
    name: 'Old',
    type: 'BOOLEAN',
    unit: null,
    resetPeriod: null,
    status: 'DEPRECATED',
  },
];

describe('PlanEditor', () => {
  let createPlan: ReturnType<typeof vi.fn>;

  async function setup() {
    createPlan = vi.fn().mockReturnValue(of({ id: 'plan-1' }));
    TestBed.configureTestingModule({
      imports: [PlanEditor],
      providers: [
        provideRouter([]),
        { provide: SubscriptionsApi, useValue: { createPlan } },
        {
          provide: CatalogApi,
          useValue: { search: () => of({ items: [PRODUCT] }), features: () => of(FEATURES) },
        },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(PlanEditor);
    await fixture.whenStable();
    const editor = fixture.componentInstance as unknown as {
      form: PlanEditor['form'];
      productToAdd: string;
      addProduct(): Promise<void>;
      save(): Promise<void>;
      formErrors(): string[];
    };
    editor.form.patchValue({ code: 'notes-basic', name: 'Notes Basic', trialDays: 14 });
    editor.form.controls.prices.at(0).patchValue({ currency: 'INR', amount: 499 });
    editor.productToAdd = 'p-1';
    await editor.addProduct();
    return { fixture, editor };
  }

  it('offers only active features of an added product', async () => {
    const { editor } = await setup();
    const features = editor.form.controls.products.at(0).get('features')?.value as {
      code: string;
    }[];
    expect(features.map((f) => f.code)).toEqual(['notes', 'export']);
  });

  it('requires a limit for included counted features', async () => {
    const { editor } = await setup();
    editor.form.controls.products
      .at(0)
      .get('features')
      ?.patchValue([{ included: true }]);
    await editor.save();
    expect(createPlan).not.toHaveBeenCalled();
    expect(editor.formErrors()[0]).toContain('limit');
  });

  it('sends prices in minor units and only the included features', async () => {
    const { editor } = await setup();
    editor.form.controls.products
      .at(0)
      .get('features')
      ?.patchValue([{ included: true, limit: 100 }, { included: true }]);
    await editor.save();
    expect(createPlan).toHaveBeenCalledWith('notes-basic', {
      name: 'Notes Basic',
      description: null,
      billingPeriod: 'MONTHLY',
      trialDays: 14,
      prices: [{ currency: 'INR', amountMinor: 49900 }],
      products: [
        {
          productId: 'p-1',
          features: [
            { code: 'notes', enabled: true, limit: 100 },
            { code: 'export', enabled: true },
          ],
        },
      ],
    });
  });
});
