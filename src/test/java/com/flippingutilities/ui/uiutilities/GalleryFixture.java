package com.flippingutilities.ui.uiutilities;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.function.Supplier;

/** A named, reproducible state of a real component, kept off the plugin classpath. */
public final class GalleryFixture {
    public final String id;
    public final String title;
    public final String description;
    public final String initialState;
    public final int height;
    private final Supplier<Mounted> factory;

    public GalleryFixture(String id, String title, String description, String initialState,
                          int height, Supplier<Mounted> factory) {
        if (!id.matches("[a-z0-9-]+") || height < 1) {
            throw new IllegalArgumentException("Fixtures need a filename-safe ID and positive height");
        }
        this.id = id;
        this.title = Objects.requireNonNull(title);
        this.description = Objects.requireNonNull(description);
        this.initialState = Objects.requireNonNull(initialState);
        this.height = height;
        this.factory = Objects.requireNonNull(factory);
    }

    public static GalleryFixture component(String id, String title, String description, String initialState,
                                           int height, Supplier<JComponent> factory) {
        return new GalleryFixture(id, title, description, initialState, height,
            () -> new Mounted(factory.get(), () -> {}));
    }

    public Mounted mount() {
        requireEdt();
        return factory.get();
    }

    static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Gallery components must be used on the event dispatch thread");
        }
    }

    @Override public String toString() { return title; }

    /** Each mount owns its resources. Closing twice is safe. */
    public static final class Mounted implements AutoCloseable {
        public final JComponent component;
        private final Runnable cleanup;
        private boolean closed;

        public Mounted(JComponent component, Runnable cleanup) {
            this.component = Objects.requireNonNull(component);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        @Override public void close() {
            requireEdt();
            if (!closed) {
                closed = true;
                cleanup.run();
            }
        }
    }
}
