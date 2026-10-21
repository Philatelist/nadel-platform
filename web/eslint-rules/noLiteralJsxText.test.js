import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import { describe, it } from 'vitest';
import { noLiteralJsxText } from './noLiteralJsxText.js';

RuleTester.describe = describe;
RuleTester.it = it;

const tester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

tester.run('no-literal-jsx-text', noLiteralJsxText, {
  valid: [
    { code: 'const a = <div className="flex gap-2" key="row-1">{t.title}</div>;' },
    { code: 'const a = <div>{"·"}{"—"}{"/"}</div>;' },
    { code: 'const a = <div>{`${a}-${b}`}</div>;' },
    { code: 'const a = <div>{c ? t.yes : t.no}</div>;' },
    { code: 'const a = <div>{`${c ? t.yes : ""}`}</div>;' },
  ],
  invalid: [
    { code: 'const a = <div>Hello</div>;', errors: 1 },
    { code: 'const a = <div>{"Hello"}</div>;', errors: 1 },
    { code: 'const a = <div>{c ? "Inner" : ""}</div>;', errors: 1 },
    { code: 'const a = <div>{`Total ${n}`}</div>;', errors: 1 },
    { code: 'const a = <div>{`${c ? "Inner" : ""}`}</div>;', errors: 1 },
    { code: 'const a = <div>{`${n}${a && "Done"}`}</div>;', errors: 1 },
  ],
});
