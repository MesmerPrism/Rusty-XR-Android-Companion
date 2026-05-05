# Architecture

Rusty XR Android Companion uses a thin Android shell around phone-owned
services.

```text
Compose app
  -> companion controller
  -> catalog, local profile, diagnostics, and transport services
  -> Android USB host / TCP ADB / optional monitor adapters
```

## Design Rules

- USB-host ADB and TCP ADB live behind transport interfaces.
- No-hardware unit tests must remain useful.
- Catalogs use public Rusty XR schema shapes first, with proof-app catalog
  paths retained only as compatibility fallbacks.
- Phone-authored runtime profiles are stored as app-private JSON and merged
  with imported catalog profiles at controller load time.
- Quest APK bytes stay out of source.
- Optional native monitor runtimes stay disabled until their release and
  license posture is audited.
- Android-specific storage and permissions stay in this app shell rather than
  Rusty XR core.
