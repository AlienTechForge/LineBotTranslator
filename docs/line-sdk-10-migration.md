# LINE Bot SDK 10 migration baseline

## Supported baseline

| Component | Version | Reason |
| --- | --- | --- |
| Java | 17+ | LINE Bot SDK 10 requires Java 17 or newer. |
| Spring Boot | 4.1.x | LINE Bot SDK 10.1 is built against Spring Boot 4.1. |
| LINE Bot SDK for Java | 10.1.x | Current supported SDK line selected for new Messaging API models and clients. |

The application pins LINE SDK `10.1.0` and Spring Boot `4.1.0`. Patch upgrades may be
accepted after the same webhook and client contract suite passes.

## Breaking changes handled

- Replaced the removed SDK 6 aggregate modules with the web MVC, handler, and client modules.
- Moved webhook events to `com.linecorp.bot.webhook.model` and outgoing messages to
  `com.linecorp.bot.messaging.model`.
- Migrated `LineMessagingClient` and `LineBlobClient` calls to `MessagingApiClient` and
  `MessagingApiBlobClient`, including `Result.body()` handling.
- Updated webhook handlers to receive the event and its typed message content as separate
  arguments.
- Migrated Spring Boot 4 test starters and REST test imports.
- Registered the custom `MongoClient` as a Bean so the readiness group retains the `mongo`
  health contributor.
- Replaced optional constructor autowiring with `ObjectProvider` for Spring Framework 7.

## Verification

Run MongoDB and execute:

```bash
MONGODB_URI=mongodb://localhost:27017/linebot_translator_test ./mvnw clean verify
./mvnw dependency:tree -Dincludes=com.linecorp.bot,org.springframework.boot
docker build -t linebot-translator:line-sdk-10 .
```

The contract suite covers signed text and image webhooks, reply messages, profile lookup,
push/broadcast requests, liveness, and Mongo-backed readiness. The dependency tree must not
contain a LINE SDK version older than 10.1 or a Spring Boot version older than 4.1.

## Rollback

Production deploys use immutable Git SHA image tags. If startup, webhook handling, or readiness
fails, the deployment workflow removes the new container and starts the previous known-good
image. Do not attempt a source-level downgrade to SDK 6 on the same branch because its modules,
models, annotations, and client APIs are incompatible with SDK 10.

For a manual emergency rollback, redeploy the last successful Git SHA from GHCR and confirm
`/actuator/health/readiness` before restoring traffic.
