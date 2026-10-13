import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CompaniesApi } from '../../../core/api/companies.api';
import { CompanyCreate } from './company-create';

describe('CompanyCreate', () => {
  function setup(create: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
      imports: [CompanyCreate],
      providers: [provideRouter([]), { provide: CompaniesApi, useValue: { create } }],
    });
    const fixture = TestBed.createComponent(CompanyCreate);
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, id: string, value: string) {
    const input = el.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  it('validates required fields in the browser before calling the API', async () => {
    const create = vi.fn();
    const { fixture, el } = setup(create);
    await fixture.whenStable();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(create).not.toHaveBeenCalled();
    expect(el.querySelector('#displayName-error')?.textContent).toContain('required');
    expect(el.querySelector('#adminEmail-error')?.textContent).toContain('required');
  });

  it('sends the company with its first administrator and opens it', async () => {
    const create = vi.fn().mockReturnValue(of({ id: 'c-new' }));
    const { fixture, el } = setup(create);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await fixture.whenStable();
    type(el, 'displayName', 'Acme');
    type(el, 'legalName', 'Acme Pvt Ltd');
    type(el, 'code', 'acme');
    type(el, 'adminEmail', 'owner@acme.test');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'acme',
        country: 'IN',
        initialAdmin: { email: 'owner@acme.test', firstName: null, lastName: null },
      }),
    );
    expect(navigate).toHaveBeenCalledWith(['/companies', 'c-new']);
  });

  it('shows server-side errors next to the field and conflicts above the form', async () => {
    const create = vi
      .fn()
      .mockReturnValueOnce(
        throwError(() => ({
          status: 400,
          code: 'VALIDATION_ERROR',
          message: 'x',
          fieldErrors: [
            {
              field: 'timeZone',
              code: 'AssertTrue',
              message: 'timeZone must be a valid IANA time zone',
            },
          ],
        })),
      )
      .mockReturnValueOnce(
        throwError(() => ({
          status: 409,
          code: 'CONFLICT',
          message: "Company code 'acme' is already in use.",
          fieldErrors: [],
        })),
      );
    const { fixture, el } = setup(create);
    await fixture.whenStable();
    type(el, 'displayName', 'Acme');
    type(el, 'legalName', 'Acme Pvt Ltd');
    type(el, 'code', 'acme');
    type(el, 'adminEmail', 'owner@acme.test');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(el.querySelector('#timeZone-error')?.textContent).toContain('IANA');

    type(el, 'timeZone', 'Asia/Kolkata');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('already in use');
  });
});
