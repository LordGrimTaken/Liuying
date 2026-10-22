// "#/search?q=..." deep link: show Today underneath and open the search overlay.
import { today } from './today.js';
import { openSearch } from './searchOverlay.js';

export function search({ query }) {
  const t = today();
  openSearch(query.q || '');
  return { destroy() { t?.destroy?.(); } };
}
