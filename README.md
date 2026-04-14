## Nateclipse

This Eclipse plugin improves your Java coding experience.

## JDT API

An Eclipse plugin exposes JDT functionality over HTTP so coding agents can use it.

Eclipse already has all your Java projects, builds incrementally in the background, and keeps an extensive symbol database. This plugin lets your coding agent efficiently explore the codebase, organize imports, and quickly check compilation succeeds, without wasting tokens on `grep`. It also provides entire the classpath of an Eclipse project, with all dependencies, allowing the agent to run code in your projects.

## Pi extensions

Extensions are provided for the fantastic [Pi](https://pi.dev) coding harness are provided.

### nateclipse.ts

This extension makes it easy for coding agents to use the JDT API. Tools provided:

* `java_grep` Grep source files of Java types matched by name or pattern.
* `java_members` Show fields and methods of a Java type.
* `java_method` Show the source code of a Java method, without over/under reading.
* `java_find_type` Search Java types by name or wildcard pattern.
* `java_organize_imports` Automatically add/remove Java imports, with conflict resolution. If there is only 1 conflict it is resolved automatically, without using an extra turn.
* `java_errors` Report Java compilation errors and warnings. Eclipse builds in the background, so this is very fast.
* `java_references` Show all references to a Java type, method, or field.
* `java_hierarchy` Show subtypes/implementors, supertypes, or full class hierarchy.
* `java_callers` Show all callers of a Java method.
* `java_classpath` Provides the classpath for a Java project and all dependencies, so main classes can be run in the project.

Also Pi's built-in `read` tool is enhanced with a `type` parameter for reading Java source by type name rather than filesystem path. By specifying a type instead of a filesystem path, coding agents don't need to find or guess at where source files are located.

### edit.ts

This extension improves the edit tool by providing context when edits fail:

- When there is no match, returns a fuzzy match for context to save a turn.
- When edits fail due to multiple occurrences, returns minimal unique context to save a turn.
- Prefixes `No edits made.` when edits fail to make it clear.

### json-fix.ts

Some LLM APIs give literal tabs and other bad data in their JSON response, stopping the agent. This extension monkey patches `fetch` to santize control characters in SSE streams that break `JSON.parse`. It fixes this error:

```
Error: Bad control character in string literal in JSON at position 123
```

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

To install the Pi extension, copy or symlink them:

```
cp pi-extensions/*.ts ~/.pi/agent/extensions/
```
