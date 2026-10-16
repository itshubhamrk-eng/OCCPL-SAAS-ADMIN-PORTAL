import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  const root = document.documentElement;

  beforeEach(() => {
    localStorage.clear();
    root.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    root.removeAttribute('data-theme');
  });

  it('leaves the choice to the browser until the user picks one', () => {
    const theme = TestBed.inject(ThemeService);
    theme.init();
    expect(root.hasAttribute('data-theme')).toBe(false);
    expect(['light', 'dark']).toContain(theme.effective());
  });

  it('toggles, applies and remembers the choice', () => {
    const theme = TestBed.inject(ThemeService);
    theme.init();
    theme.set('light');
    theme.toggle();
    expect(theme.effective()).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('occpl.theme')).toBe('dark');
    theme.toggle();
    expect(root.getAttribute('data-theme')).toBe('light');
  });

  it('restores the stored choice on start', () => {
    localStorage.setItem('occpl.theme', 'dark');
    const theme = TestBed.inject(ThemeService);
    theme.init();
    expect(theme.effective()).toBe('dark');
    expect(root.getAttribute('data-theme')).toBe('dark');
  });

  it('ignores an unknown stored value and blocked storage', () => {
    localStorage.setItem('occpl.theme', 'purple');
    const theme = TestBed.inject(ThemeService);
    theme.init();
    expect(root.hasAttribute('data-theme')).toBe(false);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => theme.set('dark')).not.toThrow();
    expect(root.getAttribute('data-theme')).toBe('dark');
  });
});
