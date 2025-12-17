# Template repository for your own maze bots

This template repository will kickstart the development of your own maze bot.
The idea is, that you don't have to set up the project for yourself and are able to focus on your bot development.
This project uses Gradle, mainly because in some environments the jitpack service will not work everywhere with Maven
(it depends on the Maven settings).
The [main project](https://github.com/strauss/maze/tree/main) is only available at jitpack, not at maven central.
The easiest way around it is not using Maven.

If you want to use Maven at all cost, you will have to set everything up yourself.
The main project will at least tell you the required dependencies to use.

## Quick start

- Create a new repository using this repository as template.
- Decide on a language for your first bot (Java or Kotlin) and focus on the corresponding module.
- If you want to create a new bot, read the
  [bot creation documentation](https://github.com/strauss/maze/blob/main/mazegame-client-ktor/doc/bot.md)
- If you want to migrate an existing bot, read the
  [bot migration documentation](https://github.com/strauss/maze/blob/main/mazegame-client-ktor/doc/bot_migration.md)
- In both cases, if you plan on adding some bot controls and/or a visualization, read the
  [bot ui documentation](https://github.com/strauss/maze/blob/main/mazegame-client-ktor/doc/bot_ui.md)
- If you just want to play around with the human bots, just start the
  [UiLauncher](ui-wrapper/src/main/java/de/dreamcube/mazegame/UiLauncher.java) and connect to a server
- If you want to start a local server
    - Clone and build the [main project](https://github.com/strauss/maze/tree/main)
        - Alternatively, use the binary jar file that might have been given to you
    - Consult the [server documentation](https://github.com/strauss/maze/blob/main/mazegame-server-ktor/README.md) and
      start the server

## Module overview

The project consists of three submodules.

- `ui-wrapper` contains a main class for starting the ui client. It is a convenience class that allows for incorporating
  all implemented bot strategies from this project in the classpath.
- `java-bots` here you can implement your bot(s) in Java.
- `kt-bots` here you can implement bot(s) in Kotlin.

If you don't want the Java or Kotlin submodule in your project, you can just remove it.
Please note: this also requires you to remove the inclusion from [settings.gradle.kts](settings.gradle.kts).

If you don't want the example implementation of "the other language" to show up in your client, just remove its `@Bot`
annotation or the whole class.

If you are told to raise the mazegame version, you do so in the file [libs.versions.toml](gradle/libs.versions.toml).
