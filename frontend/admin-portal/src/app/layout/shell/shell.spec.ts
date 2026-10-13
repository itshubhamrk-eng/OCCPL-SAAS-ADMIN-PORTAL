import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ErrorNotifier } from '../../core/errors/error-notifier';
import { Shell } from './shell';

describe('Shell', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [Shell], providers: [provideRouter([])] });
  });

  it('renders the brand and no alert by default', async () => {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.brand-name')?.textContent).toContain('OCCPL Innovations');
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows and dismisses the current API error', async () => {
    const fixture = TestBed.createComponent(Shell);
    const notifier = TestBed.inject(ErrorNotifier);
    notifier.notify({
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'Something failed.',
      correlationId: 'ref-123456',
      fieldErrors: [],
    });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Something failed.');
    expect(el.querySelector('.alert-reference')?.textContent).toContain('ref-123456');

    (el.querySelector('.alert-dismiss') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });
});
