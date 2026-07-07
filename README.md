## Nateclipse

This Eclipse plugin improves your Java coding experience.

## JDT API

An Eclipse plugin exposes JDT functionality over HTTP so coding agents (or other tools) can use it.

Eclipse already has all your Java projects, builds incrementally in the background, and keeps an extensive symbol database. This plugin lets your coding agent efficiently explore the codebase, organize imports, check compilation succeeds, and more, without wasting tokens on `grep`. It also provides entire the classpath of an Eclipse project, with all dependencies, allowing the agent to run code in your projects.

## Installation

Get the JAR from the [latest release](https://github.com/EsotericSoftware/Nateclipse/releases), put it in your `Eclipse/dropins` folder, then restart Eclipse.

To install the Pi extensions:

```
pi install git:github.com/EsotericSoftware/Nateclipse
```

The `java_*` Pi tools require Eclipse to be running with the Nateclipse plugin loaded.

## Pi extensions

Extensions are provided for the fantastic [Pi coding agent](https://pi.dev). Each has settings for number of lines shown.

### nateclipse.ts

This extension gives coding agents access to the JDT API. Semantic understanding of Java source makes code spelunking efficient. Tools reference types by name or wildcard rather than file path, so agents don't waste tokens guessing at source locations across many projects.

Tools provided:

* `java_grep` Grep source files of Java types matched by name or pattern. and results show the enclosing method/type.
* `java_members` Show fields and methods of a Java type and inherited members.
* `java_type` Show a Java type's source by name or wildcard pattern. Lists results if multiple are found.
* `java_method` Show the source code of a Java method, without over/under reading. Also includes source for super calls to reduce turns.
* `java_organize_imports` Automatically add/remove Java imports, with conflict resolution. If there is only 1 conflict it is resolved automatically, without using an extra turn.
* `java_errors` Report Java compilation errors and warnings. Eclipse builds in the background, so this is very fast.
* `java_references` Show all references to a Java type, method, or field.
* `java_hierarchy` Show subtypes/implementors, supertypes, or full class hierarchy.
* `java_callers` Show all callers of a Java method.
* `java_classpath` Provides the classpath for a Java project and all dependencies, so main classes can be run in the project.

Also, at the Pi prompt press `ctrl+space` to complete type names. `Name.` completes static members and nested types. `Name#` completes instance members, including inherited. Bind`ctrl+space` to `\u001b[32;5u` if your terminal eats `\u0000` (as Windows Terminal does).

![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/types.png?raw=true)

![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/members.png?raw=true)

### edit.ts

This extension improves the edit tool by providing context when edits fail:

- When there are no matches, returns a fuzzy match for context to save a turn.
- When edits fail due to multiple occurrences, returns minimal unique context to save a turn.
- Prefixes `No edits made.` when edits fail to make it clear.

### grep.ts

This extension provides a grep tool. It gives nicer output than making the agent use bash grep, provides hints for recovery when there are no matches, and ignores `.git` and other folders. Agent usage matches bash grep, unlike Pi's grep tool (disabled by default) that has its own parameters.

### filter.ts

This extension collapses agent turns once they are complete, greatly reducing session history noise. `ctrl+F` to toggle, for when you actually need to read that junk.

### image-pruner.ts

This extension prunes old image blocks from the LLM request context. If the agent is reading 5+ images, this prevents your context from filling up too quickly.

### usage.ts

This extension shows Codex or Claude 7 day and 5 hour usage: a percentage for usage remaining and the amount of time until usage is refreshed. Shares across Pi instances so you aren't hammering the remote service.

### retry.ts

This extension automatically retries if the agent fails with a retryable error not covered by pi's built-in retry. Also adds `/retry` or pressing `enter` on an empty editor to retry the last prompt.

### read.ts

This extension delegates to Pi's read tool, so it provides the exact same functionality, but it renders using styling consistent with all other Nateclipse tools. With `filter.ts` you'll rarely look at it anyway.

## Completion sorting

Eclipse seems to sort completions randomly. This plugin makes it smarter by tracking your most recently used types and projects. Types defined in the same file and project are preferred, and other reasonable logic. The types you are likely to choose appear higher in the list, just imagine.

The Open Type dialog also has similar improvements, with slightly different rules that make sense there.

## Tabs

Better tabs:

* Removes the `.java` suffix so tabs are shorter.
* Removes the close button so tabs are shorter.
* Increases the number of characters for a tab to be truncated with a ellipsis to 100 so tabs are longer, but you can actually tell them apart.

Before:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/before.png?raw=true)

After:
![](https://github.com/EsotericSoftware/Nateclipse/blob/main/screenshots/after.png?raw=true)
