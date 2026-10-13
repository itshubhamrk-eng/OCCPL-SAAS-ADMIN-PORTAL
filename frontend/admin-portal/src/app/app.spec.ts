import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App routing', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter(routes)] });
  });

  it('renders the home page inside the shell', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('occpl-shell')).not.toBeNull();
    expect(el.querySelector('h1')?.textContent).toContain('OCCPL Admin Portal');
  });

  it('renders the not-found page for unknown URLs', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/does/not/exist');
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toContain(
      'Page not found',
    );
  });
});
