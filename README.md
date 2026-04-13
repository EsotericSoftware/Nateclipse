## Nateclipse

This Eclipse plugin improves your Java coding experience.

## JDT HTTP API

Eclipse JDT functionality is exposed over HTTP so coding agents can use it.

Eclipse already has your project setup, symbol database, and builds incrementally in the background. This plugin lets your clanker check compilation, efficiently explore the codebase, and organize imports without wasting tokens on `grep` and manual edits.

Tools provided:

* `jdt_errors` check compilation errors/warnings (refreshes workspace, waits for build)
* `jdt_references` find all references to a type, method, or field (shows enclosing method)
* `jdt_hierarchy` subtypes, supertypes, or full hierarchy (optionally filtered to method overrides)
* `jdt_search_type` find types by name (supports `*` and `?`)
* `jdt_members` list fields and methods with signatures, modifiers, line numbers (includes inherited)
* `jdt_organize_imports` add missing imports, remove unused, resolve ambiguous types

## Tabs

Better tabs:

* Removes the `.java` suffix so tabs are shorter.
* Removes the close button so tabs are shorter.
* Increases the number of characters for a tab to be truncated with a ellipsis to 100 so tabs are longer, but you can actually tell them apart.

Before:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/before.png?raw=true)

After:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/after.png?raw=true)

## Installation

Get the JAR from the [latest release](https://github.com/EsotericSoftware/Nateclipse/releases) and put it in your `Eclipse/dropins` folder.

A [pi](https://pi.dev) extension for the JDT API is included in `pi-extension/`. To install, copy or symlink it:

```
cp pi-extension/nateclipse.ts ~/.pi/agent/extensions/
```
