## Nateclipse

This Eclipse plugin improves your Java coding experience.

## JDT HTTP API

Eclipse JDT functionality is exposed over HTTP so coding agents can use it.

Eclipse already has your project setup, builds incrementally in the background, and keeps an extensive symbol database. This plugin lets your coding agent efficiently explore the codebase, organize imports, and quickly check compilation succeeds, without wasting tokens on `grep` and manual edits. It can also access the classpath of an Eclipse project, with all dependencies, to run code in the project.

Tools provided:

* `java_grep` Grep source files of Java types matched by name or pattern.
* `java_members` Show fields and methods of a Java type.
* `java_method` Show the source code of a Java method, without over/under reading.
* `java_find_type` Search Java types by name or wildcard pattern.
* `java_organize_imports` Automatically add/remove Java imports, with conflict resolution.
* `java_errors` Report Java compilation errors and warnings.
* `java_references` Show all references to a Java type, method, or field.
* `java_hierarchy` Show subtypes/implementors, supertypes, or full class hierarchy.
* `java_callers` Show all callers of a Java method.
* `java_classpath` Provides the classpath for a Java project and all dependencies, so main classes can be run in the project.

The `read` tool is enhanced with a `type` parameter for reading Java source by type name rather than filesystem path.

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
