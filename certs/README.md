# TLS truststore for an internal AEM certificate

Use this directory when the AEM connectivity probe reports DOWN with
`category: "unreachable"` **and** the application log shows:

```
WARN  ... AEM probe for tool 'searchContent' is DOWN (unreachable):
      SSLHandshakeException: PKIX path building failed: unable to find valid
      certification path to requested target
```

That message means the JVM doesn't trust the AEM author's TLS certificate —
almost always because it is self-signed or issued by an internal CA that isn't
in the JVM's default `cacerts`. The fix is to **trust the certificate**, never
to disable verification.

> The truststore file belongs here as **`certs/java.cacerts`** — that's the path
> the pre-wired `compose.yaml` blocks expect. Truststores and certs in this
> directory (`*.jks`, `*.p12`, `*.pem`, `*.crt`, `*.cer`, anything matching
> `*cacerts*`) are gitignored; only this README and `.gitkeep` are committed.

There are two ways to produce `certs/java.cacerts`. If your org already hands out
a `cacerts` that trusts the internal CA, use Option A — it's a drop-in. Otherwise
build one with Option B.

## Option A — reuse an existing `cacerts`

If you already have a `java.cacerts` (e.g. provided by your platform team, often
the full public CA set **plus** your internal CA), just place it here:

```bash
cp /path/to/java.cacerts certs/java.cacerts
```

Verify it actually trusts the AEM/internal CA before relying on it:

```bash
keytool -list -keystore certs/java.cacerts -storepass changeit \
  | grep -i -E 'your-ca|aem'
```

`changeit` is the stock password — use your org's if it was changed, and keep it
in sync with `JAVA_TOOL_OPTIONS` in `compose.yaml`. A `cacerts` seeded from the
full JDK set keeps public HTTPS working too, so the "replaces, not augments"
caveat below doesn't bite.

## Option B — build a truststore from the cert

Prefer your **internal CA / root certificate** (from the platform team) over the
AEM leaf cert — importing the CA survives certificate rotation, whereas the leaf
breaks on every renewal.

If you only have access to the leaf, fetch it from a host that can reach AEM:

```bash
openssl s_client -connect author.internal.example.com:4502 -showcerts </dev/null \
  | openssl x509 -outform PEM > certs/aem-author.pem
```

Then import it into `certs/java.cacerts`:

```bash
keytool -importcert -noprompt -alias aem-author \
  -file certs/aem-author.pem \
  -keystore certs/java.cacerts \
  -storepass changeit
```

> **Replaces, not augments.** `-Djavax.net.ssl.trustStore` makes the JVM use
> *only* this truststore. That's fine when the server talks solely to internal
> AEM. If it must also reach public HTTPS endpoints, seed the store from the
> JDK's default `cacerts` first (this is effectively Option A):
>
> ```bash
> cp "$JAVA_HOME/lib/security/cacerts" certs/java.cacerts
> keytool -storepasswd -keystore certs/java.cacerts \
>   -storepass changeit -new changeit   # cacerts default pass is also 'changeit'
> # then run the -importcert from above to add the AEM/CA cert
> ```

## Wire it into Compose

Uncomment the `volumes:` mount and the `JAVA_TOOL_OPTIONS` override in
`compose.yaml` (both are pre-written next to the `AEM_BASE_URL` entry), then:

```bash
docker compose up -d --build
curl -sS http://localhost:8080/actuator/health/aem-search | jq
```

The `category` should flip from `unreachable` to `unauthorized`, `not_found`,
or — once everything lines up — disappear as the probe reports UP.

## Not a trust problem?

- **`ConnectException` / `UnknownHostException`** in the log → wrong host/port,
  DNS, or a blocked network path, not TLS. A truststore won't help.
- **Hostname mismatch** (the handshake error mentions the cert CN/SAN not
  matching the host) → use the hostname the certificate was issued for in
  `AEM_BASE_URL` rather than working around it.
- To watch the full handshake once, add `-Djavax.net.debug=ssl:handshake` to
  `JAVA_TOOL_OPTIONS` for a single run.
