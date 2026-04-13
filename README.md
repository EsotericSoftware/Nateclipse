## Nateclipse

This Eclipse plugin improves your Java coding experience.

## Tabs

Better tabs:

* Removes the `.java` suffix so tabs are shorter.
* Removes the close button so tabs are shorter.
* Increases the number of characters for a tab to be truncated with a ellipsis to 100 so tabs are longer, but you can actually tell them apart.

## JDT HTTP API

Eclipse JDT functionality is exposed over HTTP (port 9001) so coding agents can use it.

A [pi](https://pi.dev) extension is included in `pi-extension/`. To install, copy or symlink it:

```
cp pi-extension/nateclipse.ts ~/.pi/agent/extensions/
```

Tools provided:

* `jdt_errors` check compilation errors/warnings (refreshes workspace, waits for build)
* `jdt_references` find all references to a type, method, or field (shows enclosing method)
* `jdt_hierarchy` subtypes, supertypes, or full hierarchy (optionally filtered to method overrides)
* `jdt_search_type` find types by name (supports `*` and `?`)
* `jdt_members` list fields and methods with signatures, modifiers, line numbers (includes inherited)
* `jdt_organize_imports` add missing imports, remove unused, resolve ambiguous types

## Installation

Get the JAR from the [latest release](https://github.com/EsotericSoftware/Nateclipse/releases) and put it in your `Eclipse/dropins` folder.

## Screenshots

Before:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/before.png?raw=true)

After:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/after.png?raw=true)
