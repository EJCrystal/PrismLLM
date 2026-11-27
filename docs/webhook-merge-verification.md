# PR merge webhook verification

This documentation-only change is an owner-authorized smoke test of the existing deployment automation.

- Test date: 2026-11-27.
- Trigger under test: a pull request merged into `main` in this repository.
- Expected observation: webhook reception, an automatic task run, and the existing deployment workflow.
- A pull request closed without merging must not deploy.

This file does not change application behavior, build settings, or runtime configuration. Test results must be established from webhook and automation run records, not assumed from merging the pull request.
