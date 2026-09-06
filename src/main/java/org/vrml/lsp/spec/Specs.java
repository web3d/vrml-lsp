package org.vrml.lsp.spec;

/**
 * The generated table, loaded once per process.
 *
 * <p>{@link Spec#load()} reads and parses the shipped JSON, which is a few milliseconds of work no
 * request should pay for twice: diagnostics run on every keystroke and completion joins the same
 * rows. The holder idiom gets the laziness and the one-time initialisation without a lock - the
 * JVM guarantees a class is initialised once, and the server does not need the table before the
 * first document is analysed.
 */
public final class Specs {

    private Specs() {
    }

    /** The table this jar was built with; see {@code mvn -Pspecgen}. */
    public static Spec standard() {
        return Holder.SPEC;
    }

    private static final class Holder {
        private static final Spec SPEC = Spec.load();
    }
}
