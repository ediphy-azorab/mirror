package mirror;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.io.FileUtils;
import org.jooq.lambda.Unchecked;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;

/**
 * Recursively watches a directory for changes and sends them to a BlockingQueue for processing.
 *
 * All of the events that we fire should use paths relative to {@code rootDirectory},
 * e.g. if we're watching {@code /home/user/code/}, and {@code project-a/foo.txt changes},
 * the path of the event should be {@code project-a/foo.txt}.
 */
public class FileWatcher extends AbstractThreaded {

  // Use a synchronizedBiMap because technically performInitialScan and startPolling use different threads
  private final BiMap<WatchKey, Path> watchedDirectories = Maps.synchronizedBiMap(HashBiMap.create());
  private final Path rootDirectory;
  private final BlockingQueue<Update> queue;
  private final WatchService watchService;

  public FileWatcher(WatchService watchService, Path rootDirectory, BlockingQueue<Update> queue) {
    this.watchService = watchService;
    this.rootDirectory = rootDirectory;
    this.queue = queue;
  }

  /**
   * Initializes watches on the rootDirectory, and returns a list of all of
   * the file paths found while setting up listening hooks.
   *
   * This scan is performed on-thread and so this method blocks until complete.
   */
  public List<Update> performInitialScan() throws IOException, InterruptedException {
    // use onChangedPath because it has some try/catch logic
    onChangedPath(rootDirectory);
    List<Update> updates = new ArrayList<>(queue.size());
    queue.drainTo(updates);
    return updates;
  }

  @Override
  protected void doStop() {
    try {
      watchService.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void pollLoop() {
    while (true) {
      try {
        WatchKey watchKey = watchService.take();
        // We can't use this:
        // Path parentDir = (Path) watchKey.watchable();
        // Because it might be stale when the directory renames, see:
        // https://bugs.openjdk.java.net/browse/JDK-7057783
        Path parentDir = watchedDirectories.get(watchKey);
        for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
          WatchEvent.Kind<?> eventKind = watchEvent.kind();
          if (eventKind == OVERFLOW) {
            throw new RuntimeException("Overflow");
          }
          if (parentDir == null) {
            log.error("Missing parentDir for " + watchKey + "/" + watchEvent.context());
            continue;
          }
          Path child = parentDir.resolve((Path) watchEvent.context());
          if (eventKind == ENTRY_CREATE || eventKind == ENTRY_MODIFY) {
            onChangedPath(child);
          } else if (eventKind == ENTRY_DELETE) {
            onRemovedPath(child);
          }
        }
        watchKey.reset();
      } catch (IOException io) {
        throw new RuntimeException(io);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      } catch (ClosedWatchServiceException e) {
        break; // shutting down
      }
    }
  }

  private void onChangedPath(Path path) throws IOException, InterruptedException {
    // always recurse into directories so that even if we're excluding target/*,
    // if we are including target/scala-2.10/src_managed, then we can match those
    // paths even though we're ignoring some of the cruft around it
    try {
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        onChangedDirectory(path);
      } else if (Files.isSymbolicLink(path)) {
        onChangedSymbolicLink(path);
      } else {
        onChangedFile(path);
      }
    } catch (NoSuchFileException | FileNotFoundException e) {
      // if a file gets deleted while getting the mod time/etc., just ignore it
    }
  }

  private void onRemovedPath(Path path) throws InterruptedException {
    // Note that we can't try and guess at a mod time, because System.currentTimeMillis might
    // actually already be stale, if a file was quickly deleted then recreated, and both events
    // are in our queue. (E.g. the new file's mod time could be after our guess when we see the delete
    // event.)
    queue.put(Update.newBuilder().setPath(toRelativePath(path)).setDelete(true).setLocal(true).build());
    // in case this was a deleted directory, we'll want to start watching it again if it's re-created
    WatchKey key = watchedDirectories.inverse().get(path);
    if (key != null) {
      watchedDirectories.remove(key);
      key.cancel();
    }
  }

  private void onChangedDirectory(Path directory) throws IOException, InterruptedException {
    // for either new or changed directories, always emit an Update event
    queue.put(Update //
      .newBuilder()
      .setPath(toRelativePath(directory))
      .setDirectory(true)
      .setLocal(true)
      .setModTime(lastModified(directory))
      .build());
    // but only setup a new watcher for new directories
    if (!watchedDirectories.containsValue(directory)) {
      WatchKey key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
      watchedDirectories.put(key, directory);
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
        stream.forEach(Unchecked.consumer(this::onChangedPath));
      }
    }
  }

  private void onChangedFile(Path file) throws InterruptedException, IOException {
    String relativePath = toRelativePath(file);
    Update.Builder b = Update.newBuilder().setPath(relativePath).setModTime(lastModified(file)).setLocal(true);
    if (file.getFileName().toString().equals(".gitignore")) {
      b.setIgnoreString(FileUtils.readFileToString(file.toFile()));
    }
    queue.put(b.build());
  }

  private void onChangedSymbolicLink(Path path) throws IOException, InterruptedException {
    Path symlink = Files.readSymbolicLink(path);
    String targetPath;
    if (symlink.isAbsolute()) {
      targetPath = path.getParent().toAbsolutePath().relativize(symlink).toString();
    } else {
      // the symlink is already relative, so we can leave it alone, e.g. foo.txt
      targetPath = symlink.toString();
    }
    String relativePath = toRelativePath(path);
    log.trace("Symlink {}, relative={}, target={}", path, relativePath, targetPath);
    queue.put(Update.newBuilder().setPath(relativePath).setSymlink(targetPath).setModTime(lastModified(path)).setLocal(true).build());
  }

  private String toRelativePath(Path path) {
    return rootDirectory.relativize(path).toString().replace(File.separator, "/");
  }

  private static long lastModified(Path path) throws IOException {
    return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
  }

}
