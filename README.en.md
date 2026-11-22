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

## License

See [LICENSE](./LICENSE).
