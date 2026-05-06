---
paths: ["**/src/test/**", "**/src/androidTest/**"]
---

# Testing Guidelines

## Philosophy

- **Test user behavior, not code structure.** Users see text, tap buttons, scroll lists. Tests should do the same. Never assert on ViewModel internals, StateFlow values, or composable parameters directly. If the user can't see it or do it, don't test it.
- **Integration over unit.** Test composables with real state where possible — mock only external boundaries (network, system services).
- **No mocks on business logic.** Test what the user sees after an action, not whether a function was called.

## Structure

- **Flat tests, no nesting.** Each `@Test` function is self-contained — arrange, act, assert top to bottom.
- **Prefer duplication over the wrong abstraction.** Three similar tests with inline setup beats a shared helper that hides what's being tested.
- **Private helper functions for test data** (like `reading(ts, sgv)`) are fine — they create data, not hide logic.

## Compose UI Tests

- Use Robolectric (`@RunWith(RobolectricTestRunner::class)`) for Compose UI tests — no emulator needed.
- Query by semantics: `onNodeWithText`, `onNodeWithContentDescription`. Never use test tags unless semantics aren't available.
- Wrap content in `StrimmaTheme` to match production rendering.
- Use `assertDoesNotExist()` for absence, `assertIsDisplayed()` for visibility.
- Use `performScrollTo()` before asserting on off-screen elements.

## Unit Tests

- Test behavior, not implementation. If a refactor breaks a test, the test was wrong.
- Use real objects over mocks where possible. Mock only external boundaries (network, system services).
