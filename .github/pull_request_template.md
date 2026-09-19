## What & Why

<!-- Describe the change and the problem it solves. Link issues with "Closes #123". -->

## Checklist

- [ ] `mvn verify` passes locally (all tests green, no new skips)
- [ ] New behavior covered by tests (unit or MockMvc)
- [ ] User-visible strings added to **both** `messages.properties` and `messages_en.properties` (key-for-key parity)
- [ ] No browser-native dialogs introduced (use `SitemapUI` components)
- [ ] SSRF guard (`CrawlUrlPolicy`) untouched or strengthened
- [ ] Docs updated if behavior/API surface changed (README, CONTRIBUTING)

## Screenshots

<!-- UI changes: before/after screenshots. -->
