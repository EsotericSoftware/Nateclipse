
package com.esotericsoftware.nateclipse.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;

/** Short-lived cache of parsed Java source files: content + precomputed line-offset arrays.
 * <p>
 * The intent is to bridge the multi-request flow of a single logical operation (eg java_grep calls /java_type then
 * /java_enclosing on overlapping files, all within seconds). It is NOT a long-lived mirror of the workspace.
 * <p>
 * Entries are evicted when the underlying file changes on disk (via IResourceChangeListener) or when their last-access timestamp
 * is older than {@link #ttlNanos}. Lookups are cheap and thread-safe. */
public class FileCache {
	static final long ttlNanos = TimeUnit.SECONDS.toNanos(60);

	public static class Entry {
		public final String source;
		/** 0-based offset where each 1-based line begins. {@code offsets[0] == 0}. Length == line count. */
		public final int[] lineOffsets;
		volatile long lastAccess;

		Entry (String source, int[] lineOffsets) {
			this.source = source;
			this.lineOffsets = lineOffsets;
			this.lastAccess = System.nanoTime();
		}
	}

	private final ConcurrentHashMap<IFile, Entry> cache = new ConcurrentHashMap<>();
	private final IResourceChangeListener listener;
	private final ScheduledExecutorService janitor;

	public FileCache () {
		// Drop entries whose backing file is modified or deleted. Cache is short-lived so we don't try to repopulate eagerly.
		listener = event -> {
			var delta = event.getDelta();
			if (delta == null) return;
			try {
				delta.accept(d -> {
					var res = d.getResource();
					if (res instanceof IFile f) {
						if (d.getKind() == IResourceDelta.REMOVED
							|| (d.getKind() == IResourceDelta.CHANGED && (d.getFlags() & IResourceDelta.CONTENT) != 0)) {
							cache.remove(f);
						}
					}
					return true;
				});
			} catch (Exception ignored) {
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);

		janitor = Executors.newSingleThreadScheduledExecutor(r -> {
			var t = new Thread(r, "FileCache-janitor");
			t.setDaemon(true);
			return t;
		});
		janitor.scheduleAtFixedRate(this::evictStale, 30, 30, TimeUnit.SECONDS);
	}

	/** Load (or return cached) source + line offsets for a Java file. Returns null if not a source file or buffer unavailable. */
	public Entry get (IFile file) {
		if (file == null) return null;
		var existing = cache.get(file);
		if (existing != null) {
			existing.lastAccess = System.nanoTime();
			return existing;
		}
		try {
			if (!(JavaCore.create(file) instanceof ICompilationUnit cu)) return null;
			var buffer = cu.getBuffer();
			if (buffer == null) return null;
			var source = buffer.getContents();
			var entry = new Entry(source, buildLineOffsets(source));
			var prev = cache.putIfAbsent(file, entry);
			return prev != null ? prev : entry;
		} catch (Exception e) {
			return null;
		}
	}

	public Entry get (ICompilationUnit cu) {
		if (cu == null) return null;
		var resource = cu.getResource();
		return resource instanceof IFile f ? get(f) : null;
	}

	static int[] buildLineOffsets (String source) {
		int count = 1;
		for (int i = 0; i < source.length(); i++)
			if (source.charAt(i) == '\n') count++;
		var offsets = new int[count];
		offsets[0] = 0;
		int idx = 1;
		for (int i = 0; i < source.length(); i++)
			if (source.charAt(i) == '\n') offsets[idx++] = i + 1;
		return offsets;
	}

	/** 1-based line number for a 0-based character offset. O(log lines). */
	public static int lineFor (int[] offsets, int offset) {
		int lo = 0, hi = offsets.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (offsets[mid] <= offset)
				lo = mid;
			else
				hi = mid - 1;
		}
		return lo + 1;
	}

	void evictStale () {
		long cutoff = System.nanoTime() - ttlNanos;
		cache.entrySet().removeIf(e -> e.getValue().lastAccess < cutoff);
	}

	public void dispose () {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
		janitor.shutdownNow();
		cache.clear();
	}
}
