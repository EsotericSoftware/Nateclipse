## Nateclipse

This Eclipse plugin improves your Java coding experience.

## JDT API

An Eclipse plugin exposes JDT functionality over HTTP so coding agents can use it.

Eclipse already has all your Java projects, builds incrementally in the background, and keeps an extensive symbol database. This plugin lets your coding agent efficiently explore the codebase, organize imports, and quickly check compilation succeeds, without wasting tokens on `grep`. It also provides entire the classpath of an Eclipse project, with all dependencies, allowing the agent to run code in your projects.

## Pi extensions

Extensions are provided for the fantastic [Pi coding agent](https://pi.dev).

### nateclipse.ts

This extension makes it easy for coding agents to use the JDT API. Tools provided:

* `java_grep` Grep source files of Java types matched by name or pattern.
* `java_members` Show fields and methods of a Java type and inherited members.
* `java_type` Show a Java type's source by name or wildcard pattern. Lists results if multiple are found.
* `java_method` Show the source code of a Java method, without over/under reading. Also includes source for super calls to reduce turns.
* `java_organize_imports` Automatically add/remove Java imports, with conflict resolution. If there is only 1 conflict it is resolved automatically, without using an extra turn.
* `java_errors` Report Java compilation errors and warnings. Eclipse builds in the background, so this is very fast.
* `java_references` Show all references to a Java type, method, or field.
* `java_hierarchy` Show subtypes/implementors, supertypes, or full class hierarchy.
* `java_callers` Show all callers of a Java method.
* `java_classpath` Provides the classpath for a Java project and all dependencies, so main classes can be run in the project.

### edit.ts

This extension improves the edit tool by providing context when edits fail:

- When there is no match, returns a fuzzy match for context to save a turn.
- When edits fail due to multiple occurrences, returns minimal unique context to save a turn.
- Prefixes `No edits made.` when edits fail to make it clear.

### json-fix.ts

Some LLM APIs give literal tabs and other bad data in their JSON response, stopping the agent. This extension allows the agent to recover when the agent stops due to this error:

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
