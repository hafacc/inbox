// ESLint runs only to require doc comments on exported declarations; biome does everything else.
import jsdoc from "eslint-plugin-jsdoc";
import svelteParser from "svelte-eslint-parser";
import tseslint from "typescript-eslint";

const requireDocs = {
  "jsdoc/require-jsdoc": [
    "error",
    {
      publicOnly: true,
      require: {
        ArrowFunctionExpression: true,
        ClassDeclaration: true,
        FunctionDeclaration: true,
        MethodDefinition: true,
      },
      checkConstructors: false,
      checkSetters: "no-getter",
      contexts: [
        "TSInterfaceDeclaration",
        "TSTypeAliasDeclaration",
        "TSEnumDeclaration",
        "ExportNamedDeclaration > VariableDeclaration",
        "ExportDefaultDeclaration > ObjectExpression",
      ],
    },
  ],
};

export default [
  {
    ignores: [
      "**/node_modules/**",
      "**/*.spec.ts",
      "**/*.test.ts",
      // copied from latr, kept in step with it
      "shared/src/snooze-*.ts",
      "web/.svelte-kit/**",
      "web/out/**",
      "functions/lib/**",
    ],
  },
  {
    files: [
      "shared/src/**/*.ts",
      "web/src/**/*.ts",
      "functions/src/**/*.ts",
      "workers/*/src/**/*.ts",
    ],
    languageOptions: { parser: tseslint.parser },
    plugins: { jsdoc },
    rules: requireDocs,
  },
  {
    files: ["web/src/**/*.svelte"],
    languageOptions: {
      parser: svelteParser,
      parserOptions: { parser: tseslint.parser },
    },
    plugins: { jsdoc },
    rules: requireDocs,
  },
];
