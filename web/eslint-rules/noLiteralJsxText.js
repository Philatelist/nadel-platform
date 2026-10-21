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
      for (const expression of node.expressions) reportLiteralText(context, expression);
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

export const noLiteralJsxText = {
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
};
