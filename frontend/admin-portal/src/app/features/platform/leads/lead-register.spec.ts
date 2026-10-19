import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { LeadsApi } from '../../../core/api/leads.api';
import { LeadRegister } from './lead-register';

describe('LeadRegister', () => {
  function setup(register: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
      imports: [LeadRegister],
      providers: [provideRouter([]), { provide: LeadsApi, useValue: { register } }],
    });
    const fixture = TestBed.createComponent(LeadRegister);
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, id: string, value: string) {
    const input = el.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  it('validates required fields before calling the API', async () => {
    const register = vi.fn();
    const { fixture, el } = setup(register);
    await fixture.whenStable();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(register).not.toHaveBeenCalled();
    expect(el.querySelector('#l-companyName-error')?.textContent).toContain('required');
    expect(el.querySelector('#l-email-error')?.textContent).toContain('required');
  });

  it('registers the lead, omits blank optional fields and opens it', async () => {
    const register = vi.fn().mockReturnValue(of({ id: 'l-new' }));
    const { fixture, el } = setup(register);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await fixture.whenStable();
    type(el, 'l-companyName', ' Acme ');
    type(el, 'l-contactName', 'Priya');
    type(el, 'l-email', 'priya@acme.test');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(register).toHaveBeenCalledWith({
      companyName: 'Acme',
      contactName: 'Priya',
      email: 'priya@acme.test',
      phone: undefined,
      productInterest: undefined,
      source: undefined,
      notes: undefined,
    });
    expect(navigate).toHaveBeenCalledWith(['/platform/leads', 'l-new']);
  });
});
