import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const LETTER = /\p{L}/u;
const LITERAL_TEXT_MESSAGE = 'No literal UI text in JSX; take it from the i18n catalogs (useT).';

/** Strings that can end up on screen from an expression: branches, logical right sides, concatenations, templates. */
function reportLiteralText(context, node) {
  switch (node.type) {
    case 'Literal':
      if (typeof node.value === 'string' && LETTER.test(node.value)) {
        context.report({ node, message: LITERAL_TEXT_MESSAGE });
      }
      break;
    case 'TemplateLiteral':
      for (const quasi of node.quasis) {
        if (LETTER.test(quasi.value.raw)) context.report({ node: quasi, message: LITERAL_TEXT_MESSAGE });
      }
      break;
    case 'ConditionalExpression':
      reportLiteralText(context, node.consequent);
      reportLiteralText(context, node.alternate);
      break;
    case 'LogicalExpression':
      reportLiteralText(context, node.right);
      if (node.operator === '??') reportLiteralText(context, node.left);
      break;
    case 'BinaryExpression':
      if (node.operator === '+') {
        reportLiteralText(context, node.left);
        reportLiteralText(context, node.right);
      }
      break;
    case 'TSAsExpression':
    case 'TSNonNullExpression':
    case 'TSSatisfiesExpression':
      reportLiteralText(context, node.expression);
      break;
    default:
  }
}

const localPlugin = {
  rules: {
    'no-literal-jsx-text': {
      meta: { type: 'problem', schema: [] },
      create(context) {
        return {
          JSXText(node) {
            if (LETTER.test(node.value)) context.report({ node, message: LITERAL_TEXT_MESSAGE });
          },
          JSXExpressionContainer(node) {
            if (node.parent.type === 'JSXElement' || node.parent.type === 'JSXFragment') {
              reportLiteralText(context, node.expression);
            }
          },
        };
      },
    },
  },
};

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
