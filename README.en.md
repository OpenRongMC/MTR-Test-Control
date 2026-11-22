# MTR Test Control

[简体中文](./README.md) · English


A Fabric utility for checking modded blocks, screens and placement in isolated Minecraft instances. It can exercise interactions in game, inspect the client state and capture screenshots for repeatable test cases.

## What changed in the RongMC 26.3 port

This 26.3 build adapts the helper to the current Fabric interfaces. Its controls are intended for isolated test instances.

## Installation

Download **v1.0.0+26.3** from [GitHub Releases](https://github.com/OpenRongMC/MTR-Test-Control/releases/tag/v1.0.0%2B26.3) for Minecraft Java **26.3** on **Fabric** with **Java 25**. Dependencies include [Fabric API](https://modrinth.com/mod/fabric-api). Place the release JAR in the instance’s `mods/` directory. Use it in isolated test instances. Keep control interfaces accessible only locally and follow the command permission requirements. Read the original command and control documentation before installing.

## In-game commands

- `/mtrtest help` opens the test-command reference.
- `/ngntest help` selects the NeoGuanniao test set.
- `/aerotest help` selects the Aeronautics test set. Its default suite covers both Aeronautics and Simulated content.

## Local test control

The local interfaces expose state inspection and model checks, and can drive test interactions such as inventory access, block use, GUI input or screenshots. Use the [complete command and local-control reference](./README.md) for the endpoint configuration, authorization and test boundaries. Keep test controls loopback-only and use disposable worlds.

## Server endpoints

The server binds a random port on `127.0.0.1`. Find the endpoint with `/mtrtest http` and use its temporary `X-MTR-Test-Token` header for every request. In-game commands require operator permission level 2.

- `GET /health`, `/state` and `/summary` expose server and test state.
- `GET /blocks` and `/items` list registry entries, with optional namespace and count filters.
- `GET /inspect` inspects the block at the supplied coordinates.
- `POST /command` dispatches a test command on the server thread.
- `POST /suite` runs registry, enhanced, variant, redstone or Aeronautics checks. The summary includes passed, failed and skipped counts.
- `POST /wait` waits for a specified number of ticks.

## Client endpoints

The client uses its own loopback port and token. `GET /state` reports the current world, view, screen, widgets and inventory. `GET /models` reports missing models for ordinary modded block states. `POST /action` supports actions including GUI clicks, block interactions, scrolling, resource reloads and screenshots.

Wait for a screenshot file to be written before treating a capture as complete. For interaction tests, query client state after the action to confirm the expected screen opened. A command acknowledgement confirms dispatch. Tests still need to check the resulting world or UI.

## Aeronautics scenarios

The historical suite covers the `aeronautics` and `simulated` namespaces. With matching builds installed in an isolated test world, run `/aerotest suite` or invoke the `aero` suite through the server endpoint. A passing result has `ok == true`, `failed == 0` and `blockEntityFailures == 0`. The previous 1.21.11 suite documentation does not establish availability of those dependencies on 26.3.

## License

See [LICENSE](./LICENSE).
