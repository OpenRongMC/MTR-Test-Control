# MTR Test Control

[简体中文](./README.md) · English

A Fabric helper for RongMC test worlds. It supports automated checks and local game control to make transport and client issues reproducible.

## Download and installation

- Version · [v1.0.0+26.3](https://github.com/OpenRongMC/MTR-Test-Control/releases/tag/v1.0.0%2B26.3) (Release)
- Release asset · `mtr-test-control-1.0.0.26.3.jar`
- Runtime · Minecraft Java 26.3, Fabric, Java 25
- Fabric Loader · `>=0.19.3`
- Fabric mod ID · `mtr_test_control`
- Dependencies declared by the JAR · [Fabric API](https://modrinth.com/mod/fabric-api)

Place the release JAR in the instance’s `mods/` directory. Use it in isolated test instances. Keep control interfaces accessible only locally and follow the command permission requirements. Read the original command and control documentation before installing.

## How to try it

Run a read-only status check in a disposable test world before executing a scenario. Remove any control credentials from reports.

## Working from source

```sh
./gradlew tasks
```

Inspect the tasks available in this checkout with Java 25 before selecting a build task.

## Documentation and support

- [Previous README (historical guide, contributors and credits)](https://github.com/OpenRongMC/MTR-Test-Control/blob/addf1a7bff7f948cc0150a3cbdee4f64737bba07/README.md)
- [Report a RongMC port issue](https://github.com/OpenRongMC/MTR-Test-Control/issues) · Include the game version, client and server JAR names, reproduction steps and relevant logs.

## License and credits

See this repository’s [LICENSE](./LICENSE) and the original project’s license, credits and third-party asset notices.
