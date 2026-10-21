/**
 * Matcher for text that is split over child elements (for example a catalog sentence around a user-content span):
 * the innermost element whose whole text content matches.
 */
export function hasText(expected: string | RegExp): (content: string, element: Element | null) => boolean {
  const matches = (text: string) => (typeof expected === 'string' ? text === expected : expected.test(text));
  return (_content, element) =>
    element !== null &&
    matches(element.textContent ?? '') &&
    Array.from(element.children).every((child) => !matches(child.textContent ?? ''));
}
