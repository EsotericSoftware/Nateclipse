
package com.esotericsoftware.nateclipse.jdt;

import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.esotericsoftware.nateclipse.utils.Json;
import com.esotericsoftware.nateclipse.utils.TypeRanking;
import com.esotericsoftware.nateclipse.utils.TypeRanking.Classification;
import com.esotericsoftware.nateclipse.utils.TypeRanking.Origin;
import com.esotericsoftware.nateclipse.utils.WebServer.Exchange;

/** Completion for the pi prompt (Ctrl+Space). The <code>token</code> parameter is the raw text before the cursor:
 * <ul>
 * <li><code>shapea</code>, <code>SA</code>, <code>Shape*</code> — type completion. Matching is exact, prefix (case-insensitive),
 * camelCase, and wildcard; ranking mirrors the Open Type dialog ({@link TypeRanking#scoreForOpenType}), with exact name matches
 * first.
 * <li><code>Type.prefix</code> — static member completion (static fields/methods plus nested types) when the part before the last
 * dot resolves to a type; otherwise the whole token is treated as a package-qualified type pattern.
 * <li><code>Type#prefix</code> — instance member completion (non-static fields/methods, including inherited).
 * </ul>
 * The optional <code>cwd</code> parameter maps to the enclosing workspace project for same-project ranking. */
public class CompleteHandler {
	static private final int defaultLimit = 25;
	/** When an unqualified type part matches several types, members of the best-ranked few are merged. */
	static private final int memberTypesMax = 5;

	private CompleteHandler () {
	}

	static public void handle (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String token = p.require("token");
		if (token == null) return;
		int limit = p.intOpt("limit", defaultLimit);
		String activeProject = projectForCwd(p.get("cwd"));

		int hash = token.lastIndexOf('#');
		if (hash >= 0) {
			String head = token.substring(0, hash);
			var types = hasWildcards(head) ? new ArrayList<IType>() : resolveTypes(head, activeProject);
			members(exchange, types, token.substring(hash + 1), false, limit);
			return;
		}
		int dot = token.lastIndexOf('.');
		if (dot >= 0) {
			String head = token.substring(0, dot), tail = token.substring(dot + 1);
			if (!hasWildcards(head)) {
				var types = resolveTypes(head, activeProject);
				if (!types.isEmpty()) {
					members(exchange, types, tail, true, limit);
					return;
				}
			}
			types(exchange, head, tail, activeProject, limit);
			return;
		}
		types(exchange, null, token, activeProject, limit);
	}

	// ---- Type completion ----

	record TypeRow (TypeNameMatch match, int exactRank, int score) {}

	static private void types (Exchange exchange, String packagePattern, String typePattern, String activeProject, int limit)
		throws Exception {
		var matches = new LinkedHashMap<String, TypeNameMatch>();
		var requestor = new TypeNameMatchRequestor() {
			public void acceptTypeNameMatch (TypeNameMatch match) {
				if (!isIdentifier(match.getSimpleTypeName())) return; // Skip anonymous/local binary types.
				matches.putIfAbsent(match.getFullyQualifiedName(), match);
			}
		};
		// Implicit trailing * so the package/type parts behave as prefixes, like completion.
		char[] pkg = packagePattern == null || packagePattern.isEmpty() ? null
			: (packagePattern.endsWith("*") ? packagePattern : packagePattern + "*").toCharArray();

		if (typePattern.isEmpty())
			searchTypeNames(pkg, "*", SearchPattern.R_PATTERN_MATCH, requestor);
		else if (hasWildcards(typePattern)) {
			String pattern = typePattern.endsWith("*") || typePattern.endsWith("?") ? typePattern : typePattern + "*";
			searchTypeNames(pkg, pattern, SearchPattern.R_PATTERN_MATCH, requestor);
		} else {
			// Two index passes: case-insensitive prefix, plus camelCase when the pattern qualifies. Merged by FQN.
			searchTypeNames(pkg, typePattern, SearchPattern.R_PREFIX_MATCH, requestor);
			if (SearchPattern.validateMatchRule(typePattern, SearchPattern.R_CAMELCASE_MATCH) == SearchPattern.R_CAMELCASE_MATCH)
				searchTypeNames(pkg, typePattern, SearchPattern.R_CAMELCASE_MATCH, requestor);
		}

		var rows = new ArrayList<TypeRow>(matches.size());
		for (var match : matches.values())
			rows.add(new TypeRow(match, exactRank(typePattern, match.getSimpleTypeName()),
				TypeRanking.scoreForOpenType(classify(match), activeProject)));
		rows.sort( (a, b) -> {
			if (a.exactRank() != b.exactRank()) return a.exactRank() - b.exactRank();
			if (a.score() != b.score()) return b.score() - a.score();
			int c = a.match().getSimpleTypeName().compareToIgnoreCase(b.match().getSimpleTypeName());
			if (c != 0) return c;
			return a.match().getFullyQualifiedName().compareTo(b.match().getFullyQualifiedName());
		});

		var json = new Json();
		json.object();
		json.set("kind", "types");
		json.array("items");
		for (int i = 0, n = Math.min(rows.size(), limit); i < n; i++) {
			var match = rows.get(i).match();
			json.object();
			json.set("name", match.getSimpleTypeName());
			json.set("fqn", match.getFullyQualifiedName());
			String container = match.getTypeContainerName();
			if (container != null && !container.isEmpty()) json.set("container", container);
			json.pop();
		}
		json.pop();
		json.set("total", rows.size());
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	static private void searchTypeNames (char[] pkg, String typePattern, int matchRule, TypeNameMatchRequestor requestor)
		throws Exception {
		new SearchEngine().searchAllTypeNames(pkg, SearchPattern.R_PATTERN_MATCH, typePattern.toCharArray(), matchRule,
			IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(), requestor,
			IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
	}

	static private Classification classify (TypeNameMatch match) {
		try {
			IType type = match.getType();
			if (type != null) {
				var root = (IPackageFragmentRoot)type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
				if (root != null) return TypeRanking.classifyRoot(root, match.getFullyQualifiedName());
			}
		} catch (Exception ignored) {
		}
		return new Classification(Origin.UNKNOWN, null, match.getFullyQualifiedName());
	}

	// ---- Member completion ----

	record MemberRow (String name, String kind, String type, String params, String declaring, int rank, int typeIndex,
		int depth) {}

	static private void members (Exchange exchange, ArrayList<IType> types, String pattern, boolean statics, int limit)
		throws Exception {
		var rows = new ArrayList<MemberRow>();
		var seen = new HashSet<String>();
		for (int ti = 0, tn = types.size(); ti < tn; ti++) {
			try {
				collectMembers(types.get(ti), pattern, statics, ti, rows, seen);
			} catch (Exception ignored) { // Stale/unresolvable types are skipped.
			}
		}
		rows.sort( (a, b) -> {
			if (a.rank() != b.rank()) return a.rank() - b.rank();
			if (a.typeIndex() != b.typeIndex()) return a.typeIndex() - b.typeIndex();
			if (a.depth() != b.depth()) return a.depth() - b.depth();
			int c = a.name().compareToIgnoreCase(b.name());
			if (c != 0) return c;
			return (a.params() == null ? "" : a.params()).compareTo(b.params() == null ? "" : b.params());
		});

		var json = new Json();
		json.object();
		json.set("kind", "members");
		json.array("items");
		for (int i = 0, n = Math.min(rows.size(), limit); i < n; i++) {
			var r = rows.get(i);
			json.object();
			json.set("name", r.name());
			json.set("kind", r.kind());
			if (r.type() != null) json.set("type", r.type());
			if (r.params() != null) json.set("params", r.params());
			json.set("declaring", r.declaring());
			json.pop();
		}
		json.pop();
		json.set("total", rows.size());
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	static private void collectMembers (IType type, String pattern, boolean statics, int typeIndex, ArrayList<MemberRow> rows,
		HashSet<String> seen) throws Exception {
		// Declared members first, then inherited (Object excluded). Overrides dedupe to the nearest declaration.
		var chain = new ArrayList<IType>();
		chain.add(type);
		var hierarchy = type.newSupertypeHierarchy(new NullProgressMonitor());
		for (var sup : hierarchy.getAllSupertypes(type))
			if (!"java.lang.Object".equals(sup.getFullyQualifiedName())) chain.add(sup);

		for (int depth = 0, dn = chain.size(); depth < dn; depth++) {
			var t = chain.get(depth);
			boolean iface = t.isInterface();
			for (var field : t.getFields()) {
				int flags = field.getFlags();
				// Interface constants and enum constants are implicitly static even without the source modifier.
				boolean isStatic = Flags.isStatic(flags) || Flags.isEnum(flags) || iface;
				if (isStatic != statics) continue;
				if (depth > 0 && Flags.isPrivate(flags)) continue;
				String name = field.getElementName();
				int rank = matchRank(pattern, name);
				if (rank < 0) continue;
				if (!seen.add(typeIndex + ":F:" + name)) continue;
				rows.add(new MemberRow(name, "field", Signature.getSignatureSimpleName(field.getTypeSignature()), null,
					t.getElementName(), rank, typeIndex, depth));
			}
			for (var method : t.getMethods()) {
				if (method.isConstructor()) continue;
				int flags = method.getFlags();
				if (Flags.isStatic(flags) != statics) continue;
				if (depth > 0 && Flags.isPrivate(flags)) continue;
				String name = method.getElementName();
				if (!isIdentifier(name)) continue; // Skip <clinit> and synthetic names.
				int rank = matchRank(pattern, name);
				if (rank < 0) continue;
				var paramTypes = method.getParameterTypes();
				var params = new StringBuilder();
				for (int i = 0; i < paramTypes.length; i++) {
					if (i > 0) params.append(", ");
					params.append(Signature.getSignatureSimpleName(paramTypes[i]));
				}
				if (!seen.add(typeIndex + ":M:" + name + "(" + params + ")")) continue;
				rows.add(new MemberRow(name, "method", Signature.getSignatureSimpleName(method.getReturnType()), params.toString(),
					t.getElementName(), rank, typeIndex, depth));
			}
			if (statics) {
				for (var nested : t.getTypes()) {
					int flags = nested.getFlags();
					if (depth > 0 && Flags.isPrivate(flags)) continue;
					String name = nested.getElementName();
					int rank = matchRank(pattern, name);
					if (rank < 0) continue;
					if (!seen.add(typeIndex + ":T:" + name)) continue;
					rows.add(new MemberRow(name, "type", null, null, t.getElementName(), rank, typeIndex, depth));
				}
			}
		}
	}

	/** Resolve a type reference (simple name, FQN, or Outer.Inner) to concrete types, best ranked first, capped at
	 * {@link #memberTypesMax}. Unlike {@link JdtLookup#searchTypes} this includes binary types (JARs, JDK) so members of library
	 * types complete too. */
	static private ArrayList<IType> resolveTypes (String typePart, String activeProject) throws Exception {
		var result = new ArrayList<IType>();
		var direct = JdtLookup.findType(null, typePart);
		if (direct != null) {
			result.add(direct);
			return result;
		}
		if (typePart.indexOf('.') >= 0) return result; // Qualified but unresolved.
		var matches = new LinkedHashMap<String, IType>();
		collectExactTypes(typePart, true, matches);
		if (matches.isEmpty()) collectExactTypes(typePart, false, matches);
		result.addAll(matches.values());
		result.sort( (a, b) -> Integer.compare( //
			TypeRanking.scoreForOpenType(TypeRanking.classify(b, null), activeProject),
			TypeRanking.scoreForOpenType(TypeRanking.classify(a, null), activeProject)));
		while (result.size() > memberTypesMax)
			result.remove(result.size() - 1);
		return result;
	}

	static private void collectExactTypes (String name, boolean caseSensitive, LinkedHashMap<String, IType> out) throws Exception {
		var requestor = new TypeNameMatchRequestor() {
			public void acceptTypeNameMatch (TypeNameMatch match) {
				var type = match.getType();
				if (type != null) out.putIfAbsent(match.getFullyQualifiedName(), type);
			}
		};
		int rule = SearchPattern.R_EXACT_MATCH | (caseSensitive ? SearchPattern.R_CASE_SENSITIVE : 0);
		new SearchEngine().searchAllTypeNames(null, SearchPattern.R_EXACT_MATCH, name.toCharArray(), rule,
			IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(), requestor,
			IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
	}

	// ---- Matching ----

	/** Member match quality: 0 exact, 1 exact ignoring case, 2 prefix, 3 prefix ignoring case, 4 camelCase, 5 subword
	 * (pattern matches at a camel-hump or underscore boundary, eg "bo" matches getBounds), -1 no match. Wildcard patterns
	 * and the empty pattern match everything at subword quality so the alphabetical tiebreaker orders them. */
	static private int matchRank (String pattern, String name) {
		if (pattern.isEmpty()) return 5;
		if (hasWildcards(pattern)) {
			String p = pattern.endsWith("*") || pattern.endsWith("?") ? pattern : pattern + "*";
			return CharOperation.match(p.toCharArray(), name.toCharArray(), false) ? 5 : -1;
		}
		if (pattern.equals(name)) return 0;
		if (pattern.equalsIgnoreCase(name)) return 1;
		if (name.startsWith(pattern)) return 2;
		if (name.regionMatches(true, 0, pattern, 0, pattern.length())) return 3;
		if (SearchPattern.camelCaseMatch(pattern, name)) return 4;
		if (subwordMatch(pattern, name)) return 5;
		return -1;
	}

	/** True when {@code pattern} matches case-insensitively at an interior word boundary of {@code name}: an uppercase
	 * camel hump (getBounds -> Bounds), after an underscore (MAX_VALUE -> VALUE), or a letter following a digit. */
	static private boolean subwordMatch (String pattern, String name) {
		int length = pattern.length();
		for (int i = 1, n = name.length() - length; i <= n; i++) {
			char c = name.charAt(i), prev = name.charAt(i - 1);
			boolean boundary = Character.isUpperCase(c) //
				|| prev == '_' || prev == '$' //
				|| (Character.isLetter(c) && Character.isDigit(prev));
			if (boundary && name.regionMatches(true, i, pattern, 0, length)) return true;
		}
		return false;
	}

	/** 0 = case-sensitive match, 1 = case-insensitive match, 2 = no exact match. */
	static private int exactRank (String pattern, String name) {
		if (pattern.isEmpty() || hasWildcards(pattern)) return 2;
		if (pattern.equals(name)) return 0;
		if (pattern.equalsIgnoreCase(name)) return 1;
		return 2;
	}

	static private boolean hasWildcards (String s) {
		return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
	}

	static private boolean isIdentifier (String s) {
		if (s == null || s.isEmpty()) return false;
		if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
		for (int i = 1, n = s.length(); i < n; i++)
			if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
		return true;
	}

	/** Maps the client's working directory to the workspace project that contains it, for same-project ranking. Longest matching
	 * project location wins (nested projects). Null when outside the workspace. */
	static private String projectForCwd (String cwd) {
		if (cwd == null) return null;
		String norm = cwd.replace('\\', '/').toLowerCase(Locale.ROOT);
		if (!norm.endsWith("/")) norm += "/";
		String best = null;
		int bestLength = -1;
		for (var project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isAccessible()) continue;
			var location = project.getLocation();
			if (location == null) continue;
			String path = location.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
			if (!path.endsWith("/")) path += "/";
			if (norm.startsWith(path) && path.length() > bestLength) {
				best = project.getName();
				bestLength = path.length();
			}
		}
		return best;
	}
}
