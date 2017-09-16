package com.athaydes.logfx.file;

import com.athaydes.logfx.concurrency.Cancellable;
import com.athaydes.logfx.concurrency.TaskRunner;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches a file for any change that might happen to it.
 */
public class FileChangeWatcher {

    private static final Logger log = LoggerFactory.getLogger( FileChangeWatcher.class );

    private final AtomicBoolean closed = new AtomicBoolean( false );
    private final AtomicBoolean watching = new AtomicBoolean( false );
    private final File file;
    private final Cancellable watcherTask;
    private volatile Thread watcherThread;
    private volatile Runnable onChange;

    public FileChangeWatcher( File file, TaskRunner taskRunner ) {
        this.file = file;

        this.watcherTask = taskRunner.scheduleRepeatingTask(
                Duration.ofSeconds( 2 ),
                this::startIfNotWatching );
    }

    private void startIfNotWatching() {
        if ( !isClosed() && !isWatching() ) {
            log.debug( "FileWatcher is not watching the file yet, trying to start it up for file {}", file );
            if ( file.getParentFile().isDirectory() ) {
                Thread thread = watchFile( file.toPath() );
                this.watcherThread = thread;
                thread.start();
            } else {
                log.debug( "Cannot start watching file as its parent directory does not exist: {}", file );
            }
        }
    }

    public void setOnChange( Runnable onChange ) {
        this.onChange = onChange;
    }

    private Thread watchFile( Path path ) {
        return new Thread( () -> {
            WatchKey watchKey = null;
            try ( WatchService watchService = FileSystems.getDefault().newWatchService() ) {
                watchKey = path.getParent().register( watchService, new WatchEvent.Kind[]{
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.OVERFLOW,
                        StandardWatchEventKinds.ENTRY_DELETE
                }, SensitivityWatchEventModifier.HIGH );

                log.info( "Watching file " + path );
                watching.set( true );
                notifyWatcher( "started_watching" );

                while ( isWatching() ) {
                    WatchKey wk = watchService.take();
                    log.trace( "Watch key: {}", wk );
                    for ( WatchEvent<?> event : wk.pollEvents() ) {
                        //we only register "ENTRY_MODIFY" so the context is always a Path.
                        Path changed = ( Path ) event.context();
                        if ( !closed.get() && path.getFileName().equals( changed.getFileName() ) ) {
                            notifyWatcher( event.kind().name() );
                        }
                    }
                    // reset the key
                    boolean valid = wk.reset();
                    if ( !valid ) {
                        log.info( "Key has been unregistered! File watcher cannot watch file " +
                                "until it is created again: {}", file );
                        notifyWatcher( "unregistered" );
                        watching.set( false );
                    }
                }
            } catch ( InterruptedException e ) {
                log.info( "Interrupted watching file {}", file );
                closed.set( true );
            } catch ( IOException e ) {
                log.warn( "Problem watching file [{}]: {}", file, e );
                notifyWatcher( "IOException" );
            } finally {
                watching.set( false );
                if ( watchKey != null ) {
                    watchKey.cancel();
                }
            }
        } );
    }

    private void notifyWatcher( String eventKind ) {
        final Runnable toRun = onChange;
        if ( toRun != null ) {
            log.debug( "Notifying listener of change event {} on file {}",
                    eventKind, file );
            try {
                toRun.run();
            } catch ( Exception e ) {
                log.warn( "Error handling file change event", e );
            }
        } else {
            log.warn( "File change detected ({}), but no listener has been registered " +
                    "for file: {}", eventKind, file );
        }
    }

    public boolean isWatching() {
        return !isClosed() && watching.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void close() {
        if ( !closed.getAndSet( true ) ) {
            final Thread thread = watcherThread;
            watcherTask.cancel();

            if ( thread != null ) {
                thread.interrupt();
            }
        }
    }

}
