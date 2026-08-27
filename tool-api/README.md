# intouch-tool-api

MIT. One file, 189 lines: [`IToolConnector.kt`](src/main/kotlin/com/blueisle/intouch/tool/IToolConnector.kt).

Implement this interface, build a JAR, drop it in `tools/installed/`, and InTouch has a new task
type — available to every workflow, with the same scheduling, retries, credentials, logging and
alerting as anything built in. There is no registration step and no restart of anything but the
server.

## The one design rule

**A tool's only compile-time dependency is this interface.** Everything crossing the boundary is
a plain JSON string. Nothing in this file imports anything else from InTouch, and nothing in a
tool needs to.

That is not stylistic. Each plugin is loaded in **its own `URLClassLoader`**, so a tool that
wants Jackson 2.13 and a tool that wants Jackson 2.17 can sit in the same server without either
one knowing. If the boundary carried typed objects, both would have to agree with the server on
every class those objects touch, and the isolation would be theatre.

## Why this interface does not grow

Adding a method to `IToolConnector` — even with a default implementation — is how you break every
tool anyone has already built. Kotlin interface defaults compile to a `DefaultImpls` bridge that
existing JARs do not carry, so the first call into an older plugin throws `AbstractMethodError` at
runtime, in production, on somebody else's tool. We learned this against 40 shipped connector
JARs.

New capability is negotiated through the JSON contract instead — a new key that older tools simply
do not emit. The interface is frozen on purpose. `version = 8.0.0` here tracks the ABI, not the
server: the server is on 8.0.6 and this file has not changed.

## Build

```bash
./gradlew :tool-api:jar
```

Or download `intouch-tool-api-8.0.0.jar` from
[Releases](https://github.com/intouch-ai-core/intouch-ai/releases/latest).

Working implementations of this interface are in [`examples/connectors/`](../examples/connectors).
