# SubServer

## About SubServer

SubServer is a Minecraft **26.2** plugin for the BlurpWorld Paper fork. It splits a server into isolated instances backed by compressed BlurpWorld snapshots.

## Requirements

| Component | Version |
|-----------|---------|
| Server    | BlurpWorld Paper **26.2** |
| Java      | **25** (required by Paper 26.2) |
| API       | `io.papermc.paper:paper-api:26.2.local-SNAPSHOT` |

## Snapshots

Place durable `.bws` archives in `plugins/SubServer/maps`. The file name without `.bws` is the template name used by `InstanceType#addWorld`. Snapshots already loaded in BlurpWorld can also be resolved by their label or source-world name.

Temporary instance worlds are restored asynchronously and discarded on close. Worlds marked savable are snapshotted, exported back to `plugins/SubServer/maps/<template>.bws`, then unloaded.

The `.bws` archive also carries the seed, time, spawn, gamerules, weather, border, PDC and other per-world saved data. Each restored instance receives a fresh Paper world UUID so clones can coexist. Instance worlds therefore do not create Paper dimension folders or metadata files.

## Build

Publish the fork API locally, then build SubServer:

```powershell
.\gradlew.bat :paper-api:publishToMavenLocal
cd integrations\SubServer-BlurpWorld-26.2
mvn package
```

### Using SubServer

SubServer uses a templating system which works by registering your `InstanceType` (your template) inside an `InstanceFactory`. You have a few settings to configure inside the `InstanceType` object and most importantly, you need to set a runnable that will be executed after the initialization of each instance.
That is your entrypoint to control the behavior of instances.

Another important point is that for each instance, if you need to register an event listener, you must do it using the `Instance#registerListener` method. It will allow you to only catch the events of your own instance, you won't have to filter yourself which event is yours.

### Pre-generated vs on-demand instances

`InstanceType#maxInstancesCount` is capped at `InstanceType.MAX_INSTANCES_LIMIT` (10). Above
zero, the factory loop keeps that many instances open. At zero the type is **on demand**:
nothing is pre-generated and each instance comes from `InstanceFactory#createInstance`.

```java
InstanceType game = new InstanceType("my_game", false, 8);
game.setMaxInstancesCount(0);          // on demand
game.setCloseWhenEmpty(true);          // closed once everyone leaves
game.setEmptyGraceSeconds(15);
game.addWorld("my_map", false);        // restored to a UUID-prefixed memory world

factory.registerType(game);
factory.createInstance(game, instance -> instance.joinInstance(player));
```

`Instance#loadWorld` takes an optional failure callback. Snapshot preparation, import, export and persistence run asynchronously; Bukkit world attachment and unloading remain on the server thread.

## Contributors
- [LoanSpac](https://github.com/LoanSpac)
- [Clooooud](https://github.com/Clooooud)
