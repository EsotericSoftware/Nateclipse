
package com.esotericsoftware.nateclipse.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.esotericsoftware.nateclipse.Activator;

/** Tracks recently used types and projects, persisted across Eclipse restarts.
 * <p>
 * Entries are stored in insertion order; the last entry is the most recent. Ranks are returned as 0-based "how recent" indices
 * where 0 is the most recent. Access to this class is thread-safe. */
public class MruTracker {
	static private final ILog log = Platform.getLog(MruTracker.class);

	/** Hard caps. Generous, but bounded so we can rebuild indexes fast. */
	static public final int MAX_TYPES = 500;
	static public final int MAX_PROJECTS = 50;

	/** Save every N bumps. Also saved on plugin shutdown. */
	static private final int SAVE_EVERY = 10;

	static private MruTracker instance;

	static public synchronized MruTracker get () {
		if (instance == null) instance = new MruTracker();
		return instance;
	}

	/** Insertion-ordered; last entry = most recent. */
	private final LinkedHashMap<String, Long> types = new LinkedHashMap<>(128);
	private final LinkedHashMap<String, Long> projects = new LinkedHashMap<>(32);

	/** Invalidated on every mutation; rebuilt lazily in {@link #getTypeRank}/{@link #getProjectRank}. */
	private Map<String, Integer> typeRankCache;
	private Map<String, Integer> projectRankCache;

	private final File file;
	private int dirtyCount;

	private MruTracker () {
		File f = null;
		try {
			f = Activator.getDefault().getStateLocation().append("mru.txt").toFile();
		} catch (Exception ex) {
			log.error("MRU: unable to determine state file location.", ex);
		}
		file = f;
		load();
	}

	public synchronized void bumpType (String fqn, String projectName) {
		if (fqn == null || fqn.isEmpty()) return;
		long now = System.currentTimeMillis();
		types.remove(fqn);
		types.put(fqn, now);
		while (types.size() > MAX_TYPES)
			types.remove(types.keySet().iterator().next());
		typeRankCache = null;
		if (projectName != null && !projectName.isEmpty()) bumpProjectInternal(projectName, now);
		markDirty();
	}

	public synchronized void bumpProject (String projectName) {
		if (projectName == null || projectName.isEmpty()) return;
		bumpProjectInternal(projectName, System.currentTimeMillis());
		markDirty();
	}

	private void bumpProjectInternal (String projectName, long now) {
		projects.remove(projectName);
		projects.put(projectName, now);
		while (projects.size() > MAX_PROJECTS)
			projects.remove(projects.keySet().iterator().next());
		projectRankCache = null;
	}

	/** @return 0 = most recent, larger = older, -1 = not present. */
	public synchronized int getTypeRank (String fqn) {
		if (fqn == null) return -1;
		if (typeRankCache == null) typeRankCache = buildRankCache(types);
		Integer r = typeRankCache.get(fqn);
		return r != null ? r.intValue() : -1;
	}

	/** @return 0 = most recent, larger = older, -1 = not present. */
	public synchronized int getProjectRank (String projectName) {
		if (projectName == null) return -1;
		if (projectRankCache == null) projectRankCache = buildRankCache(projects);
		Integer r = projectRankCache.get(projectName);
		return r != null ? r.intValue() : -1;
	}

	static private Map<String, Integer> buildRankCache (LinkedHashMap<String, Long> map) {
		int size = map.size();
		Map<String, Integer> cache = new HashMap<>(size * 2);
		int i = 0;
		for (String k : map.keySet())
			cache.put(k, size - 1 - i++);
		return cache;
	}

	private void markDirty () {
		if (++dirtyCount >= SAVE_EVERY) {
			dirtyCount = 0;
			saveAsync();
		}
	}

	private void saveAsync () {
		final LinkedHashMap<String, Long> t, p;
		synchronized (this) {
			t = new LinkedHashMap<>(types);
			p = new LinkedHashMap<>(projects);
		}
		Thread thread = new Thread( () -> writeFile(t, p), "Nateclipse MRU save");
		thread.setDaemon(true);
		thread.start();
	}

	/** Force a synchronous save. Call from {@link Activator#stop}. */
	public synchronized void saveNow () {
		dirtyCount = 0;
		writeFile(new LinkedHashMap<>(types), new LinkedHashMap<>(projects));
	}

	private void writeFile (LinkedHashMap<String, Long> t, LinkedHashMap<String, Long> p) {
		if (file == null) return;
		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		try {
			file.getParentFile().mkdirs();
			try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
				for (Map.Entry<String, Long> e : t.entrySet()) {
					w.write("T\t");
					w.write(Long.toString(e.getValue()));
					w.write('\t');
					w.write(e.getKey());
					w.write('\n');
				}
				for (Map.Entry<String, Long> e : p.entrySet()) {
					w.write("P\t");
					w.write(Long.toString(e.getValue()));
					w.write('\t');
					w.write(e.getKey());
					w.write('\n');
				}
			}
			// Atomic-ish replace.
			if (file.exists()) file.delete();
			if (!tmp.renameTo(file)) log.warn("MRU: could not rename " + tmp + " -> " + file);
		} catch (Exception ex) {
			log.error("MRU: save failed.", ex);
		}
	}

	private void load () {
		if (file == null || !file.isFile()) return;
		List<String[]> typeRows = new ArrayList<>();
		List<String[]> projRows = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isEmpty()) continue;
				String[] parts = line.split("\t", 3);
				if (parts.length != 3) continue;
				if ("T".equals(parts[0]))
					typeRows.add(parts);
				else if ("P".equals(parts[0])) projRows.add(parts);
			}
		} catch (Exception ex) {
			log.error("MRU: load failed.", ex);
			return;
		}
		// Sort by timestamp ascending so the last put is the most recent.
		sortByTimestamp(typeRows);
		sortByTimestamp(projRows);
		synchronized (this) {
			for (String[] row : typeRows) {
				types.put(row[2], Long.parseLong(row[1]));
			}
			for (String[] row : projRows) {
				projects.put(row[2], Long.parseLong(row[1]));
			}
			typeRankCache = null;
			projectRankCache = null;
		}
	}

	static private void sortByTimestamp (List<String[]> rows) {
		Collections.sort(rows, (a, b) -> Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1])));
	}
}
