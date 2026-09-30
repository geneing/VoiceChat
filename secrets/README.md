# Local secrets (not committed)

Drop developer-supplied credentials here for device/debug runs. The whole
directory is gitignored (`secrets/*`); only this note is tracked. Never commit a
real secret, and never paste one into source, resources, build files, docs, or
logs — `AGENTS.md` forbids it and
`security.RepositorySecretScanTest` enforces it for the shipped paths.

## Format

One `NAME=value` entry per line; blank lines and `#` comments are ignored.

```
# secrets/opencode-go.key
OPENCODE_API_KEY=<your OpenCode Go API key>
```

## Use

```bash
# from WSL, repo root
bash scripts/push-credentials.sh
```

That copies the file into the app's **private** storage. A debug build imports it
into the AndroidKeyStore-backed store on the next launch and then deletes the
plaintext copy; only KeyStore-encrypted ciphertext remains. Release builds never
contain the import step. See [docs/credentials.md](../docs/credentials.md).

The entry name maps to a provider: `OPENCODE_API_KEY` is
[OpenCode Go](../../docs/opencode-go-adapter.md) (`opencode.ai`). Do not invent
entry names without wiring a matching import; an unknown entry is ignored and the
file is removed.
