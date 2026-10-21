import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import { noLiteralJsxText } from './eslint-rules/noLiteralJsxText.js';

const localPlugin = { rules: { 'no-literal-jsx-text': noLiteralJsxText } };

export default tseslint.config(
  { ignores: ['dist', 'playwright-report', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['**/*.test.{ts,tsx}'],
    plugins: { local: localPlugin },
    rules: {
      'local/no-literal-jsx-text': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Literal[value=/#[0-9a-fA-F]{3,8}\\b/]',
          message: 'Hex colors are not allowed here; use the design tokens (Tailwind token utilities).',
        },
        {
          selector: 'TemplateElement[value.raw=/#[0-9a-fA-F]{3,8}\\b/]',
          message: 'Hex colors are not allowed here; use the design tokens (Tailwind token utilities).',
        },
        {
          selector:
            'JSXAttribute[name.name=/^(aria-label|placeholder|title|alt)$/] > Literal[value=/\\p{L}/u]',
          message:
            'No literal UI text in aria-label/placeholder/title/alt; take it from the i18n catalogs (useT).',
        },
        {
          selector:
            'JSXAttribute[name.name=/^(aria-label|placeholder|title|alt)$/] > JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/\\p{L}/u]',
          message:
            'No literal UI text in aria-label/placeholder/title/alt; take it from the i18n catalogs (useT).',
        },
        {
          selector:
            'JSXAttribute[name.name=/^(aria-label|placeholder|title|alt)$/] > JSXExpressionContainer > Literal[value=/\\p{L}/u]',
          message:
            'No literal UI text in aria-label/placeholder/title/alt; take it from the i18n catalogs (useT).',
        },
      ],
    },
  },
  {
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: { globals: globals.node },
  },
);
